-- Incidents and private evidence (#26).
-- Either participant opens an incident on a started, done or completed appointment. It never moves money and never
-- changes the stage: a cancellation after the start, a quality complaint or a dispute about whether the service
-- happened all wait for administrative review (#31). An open incident opened before the completion blocks the
-- delivery (client confirmation, clock and any later caller of private.complete_appointment). One opened after the
-- close is a communication: it blocks and reverts nothing.
-- Evidence are concrete chat messages (text, photo or video) or notes, contributed after a visibility notice. Both
-- participants and administrators read the evidence of that incident only: never the rest of the chat or its files.

create table public.incidents (
    id uuid primary key default gen_random_uuid(),
    appointment_id uuid not null references public.appointments(id),
    opened_by uuid not null references auth.users(id),
    request_id uuid not null,
    -- cancellation: cancellation requested after the start; quality: the service happened but is disputed in quality;
    -- not_performed: dispute about whether the service happened at all.
    kind text not null constraint incidents_kind_check check (kind in ('cancellation', 'quality', 'not_performed')),
    description text not null check (length(description) between 1 and 2000),
    -- Kept apart from the stage and the money: only a quality complaint acknowledges that the service happened.
    performance text not null generated always as (case when kind = 'quality' then 'acknowledged' else 'disputed' end) stored,
    -- #31 adds the resolution; until then every incident is open.
    status text not null default 'open' constraint incidents_status_check check (status in ('open', 'resolved')),
    -- Context at the opening, kept as it was: the stage and the simulated balance are not changed by the incident.
    appointment_stage text not null,
    payment_mode text,
    pending_cents integer,
    delivered_cents integer,
    blocks_delivery boolean not null,
    created_at timestamptz not null default clock_timestamp(),
    unique (opened_by, request_id)
);
create unique index incidents_one_open on public.incidents(appointment_id) where status = 'open';
alter table public.incidents enable row level security;
revoke all on public.incidents from public, anon, authenticated;
grant select on public.incidents to authenticated;

-- Both participants of the appointment, or an administrator; nobody else learns that the incident exists.
create function public.can_read_incident(p_incident_id uuid)
returns boolean language sql stable security definer set search_path = '' as $$
    select exists (select 1 from public.incidents i join public.appointments a on a.id = i.appointment_id
        where i.id = p_incident_id
          and (auth.uid() in (a.client_id, a.professional_id)
               or exists (select 1 from private.administrators ad where ad.user_id = auth.uid())));
$$;
revoke all on function public.can_read_incident(uuid) from public, anon;
grant execute on function public.can_read_incident(uuid) to authenticated;
create policy participants_and_review_read on public.incidents for select to authenticated
    using (public.can_read_incident(id));

-- What was said at the opening cannot be rewritten, even with trusted SQL; only the review may change the status.
create function private.keep_incident_history()
returns trigger language plpgsql set search_path = '' as $$
begin
    if tg_op = 'DELETE' or (new.id, new.appointment_id, new.opened_by, new.request_id, new.kind, new.description,
        new.appointment_stage, new.payment_mode, new.pending_cents, new.delivered_cents, new.blocks_delivery, new.created_at)
        is distinct from (old.id, old.appointment_id, old.opened_by, old.request_id, old.kind, old.description,
        old.appointment_stage, old.payment_mode, old.pending_cents, old.delivered_cents, old.blocks_delivery, old.created_at) then
        raise exception using errcode = '42501', message = 'incident_history_locked';
    end if;
    return new;
end;
$$;
revoke all on function private.keep_incident_history() from public, anon, authenticated;
create trigger incidents_keep_history before update or delete on public.incidents
    for each row execute function private.keep_incident_history();

alter table public.appointment_events drop constraint appointment_events_kind_check,
    add constraint appointment_events_kind_check check (kind in ('requested', 'proposed', 'rejected', 'accepted',
        'change_proposed', 'change_accepted', 'change_rejected', 'address_correction_requested',
        'started', 'cancelled', 'done', 'completed', 'incident_opened', 'incident_evidence'));

-- The seam of #25: an open incident opened before the completion blocks every delivery.
create or replace function private.delivery_blocked(p_appointment_id uuid)
returns boolean language sql stable security definer set search_path = ''
as $$ select exists (select 1 from public.incidents i
    where i.appointment_id = p_appointment_id and i.status = 'open' and i.blocks_delivery) $$;
revoke all on function private.delivery_blocked(uuid) from public, anon, authenticated;

-- Takes the appointment row lock first, like confirm_done and the clock: an incident committed before them blocks
-- them, and one that arrives after the completion is recorded as a communication after the close.
create function public.open_incident(p_appointment_id uuid, p_request_id uuid, p_kind text, p_description text,
    p_visibility_acknowledged boolean)
returns jsonb language plpgsql security definer set search_path = ''
as $$
declare
    actor uuid := auth.uid();
    appt public.appointments;
    payment public.appointment_payments;
    result public.incidents;
begin
    perform public.current_account();
    select * into appt from public.appointments a where a.id = p_appointment_id for update;
    if appt.id is null or actor not in (appt.client_id, appt.professional_id) then
        raise exception using errcode = '42501', message = 'permission_denied';
    end if;
    if p_visibility_acknowledged is distinct from true then
        raise exception using errcode = '22023', message = 'visibility_not_acknowledged';
    end if;
    if p_request_id is null or p_kind is null or p_kind not in ('cancellation', 'quality', 'not_performed')
       or p_description is null or length(btrim(p_description)) not between 1 and 2000 then
        raise exception using errcode = '22023', message = 'invalid_incident';
    end if;
    select * into result from public.incidents i where i.opened_by = actor and i.request_id = p_request_id;
    if result.id is not null then
        if result.appointment_id <> appt.id or result.kind <> p_kind or result.description <> btrim(p_description) then
            raise exception using errcode = '22023', message = 'retry_conflict';
        end if;
        return to_jsonb(result);
    end if;
    if appt.stage not in ('started', 'done', 'completed') or (p_kind = 'cancellation' and appt.stage <> 'started') then
        raise exception using errcode = 'PT409', message = 'incompatible_state';
    end if;
    if exists (select 1 from public.incidents i where i.appointment_id = appt.id and i.status = 'open') then
        raise exception using errcode = 'PT409', message = 'incident_open';
    end if;
    select * into payment from public.appointment_payments p where p.appointment_id = appt.id;
    insert into public.incidents(appointment_id, opened_by, request_id, kind, description, appointment_stage,
        payment_mode, pending_cents, delivered_cents, blocks_delivery)
      values (appt.id, actor, p_request_id, p_kind, btrim(p_description), appt.stage,
        payment.mode, payment.pending_cents, payment.delivered_cents, appt.stage <> 'completed')
      returning * into result;
    insert into public.appointment_events(appointment_id, kind, actor_id, recipient_id)
      values (appt.id, 'incident_opened', actor,
        case when actor = appt.client_id then appt.professional_id else appt.client_id end);
    return to_jsonb(result);
end;
$$;
revoke all on function public.open_incident(uuid, uuid, text, text, boolean) from public, anon;
grant execute on function public.open_incident(uuid, uuid, text, text, boolean) to authenticated;

-- Append-only evidence. A message is copied as it was (messages are immutable already), so the review reads the
-- contributed content here and never needs chat_messages.
create table public.incident_evidence (
    id uuid primary key default gen_random_uuid(),
    incident_id uuid not null references public.incidents(id),
    author_id uuid not null references auth.users(id),
    request_id uuid not null,
    kind text not null constraint incident_evidence_kind_check check (kind in ('message', 'note')),
    note text check (length(note) between 1 and 2000),
    message_id uuid references public.chat_messages(id),
    message_author_id uuid references auth.users(id),
    message_body text,
    message_created_at timestamptz,
    attachment_kind text,
    attachment_path text,
    attachment_type text,
    created_at timestamptz not null default clock_timestamp(),
    unique (incident_id, author_id, request_id),
    unique (incident_id, message_id),
    constraint incident_evidence_content check ((kind = 'note') = (note is not null and message_id is null)
        and (kind = 'message') = (message_id is not null and note is null))
);
alter table public.incident_evidence enable row level security;
revoke all on public.incident_evidence from public, anon, authenticated;
grant select on public.incident_evidence to authenticated;
create policy participants_and_review_read on public.incident_evidence for select to authenticated
    using (public.can_read_incident(incident_id));

create function private.keep_evidence()
returns trigger language plpgsql set search_path = '' as $$
begin
    raise exception using errcode = '42501', message = 'evidence_locked';
end;
$$;
revoke all on function private.keep_evidence() from public, anon, authenticated;
create trigger incident_evidence_append_only before update or delete on public.incident_evidence
    for each row execute function private.keep_evidence();

-- Either participant adds a concrete message of the appointment's conversation (p_message_id) or a note (p_note)
-- while the incident is open. Contributing a message already contributed returns that evidence.
create function public.add_incident_evidence(p_incident_id uuid, p_request_id uuid, p_message_id uuid, p_note text,
    p_visibility_acknowledged boolean)
returns jsonb language plpgsql security definer set search_path = ''
as $$
declare
    actor uuid := auth.uid();
    appt public.appointments;
    incident public.incidents;
    message public.chat_messages;
    result public.incident_evidence;
begin
    perform public.current_account();
    select i.* into incident from public.incidents i where i.id = p_incident_id;
    -- Same lock as open_incident and the review: serialises with the status change.
    select a.* into appt from public.appointments a where a.id = incident.appointment_id for update;
    if appt.id is null or actor not in (appt.client_id, appt.professional_id) then
        raise exception using errcode = '42501', message = 'permission_denied';
    end if;
    if p_visibility_acknowledged is distinct from true then
        raise exception using errcode = '22023', message = 'visibility_not_acknowledged';
    end if;
    if p_request_id is null or (p_message_id is null) = (p_note is null)
       or (p_note is not null and length(btrim(p_note)) not between 1 and 2000) then
        raise exception using errcode = '22023', message = 'invalid_evidence';
    end if;
    if p_message_id is not null then
        select m.* into message from public.chat_messages m where m.id = p_message_id;
        if message.id is null or message.conversation_id <> appt.conversation_id then
            raise exception using errcode = '42501', message = 'permission_denied';
        end if;
    end if;
    select * into result from public.incident_evidence e
      where e.incident_id = incident.id and e.author_id = actor and e.request_id = p_request_id;
    if result.id is not null then
        if result.message_id is distinct from p_message_id or result.note is distinct from btrim(p_note) then
            raise exception using errcode = '22023', message = 'retry_conflict';
        end if;
        return to_jsonb(result);
    end if;
    select i.* into incident from public.incidents i where i.id = p_incident_id;
    if incident.status <> 'open' then
        raise exception using errcode = 'PT409', message = 'incident_closed';
    end if;
    if message.id is not null then
        select * into result from public.incident_evidence e where e.incident_id = incident.id and e.message_id = message.id;
        if result.id is not null then
            return to_jsonb(result);
        end if;
    end if;
    insert into public.incident_evidence(incident_id, author_id, request_id, kind, note, message_id, message_author_id,
        message_body, message_created_at, attachment_kind, attachment_path, attachment_type)
      values (incident.id, actor, p_request_id, case when message.id is null then 'note' else 'message' end,
        btrim(p_note), message.id, message.author_id, message.body, message.created_at, message.attachment_kind,
        message.attachment_path, message.attachment_type)
      returning * into result;
    insert into public.appointment_events(appointment_id, kind, actor_id, recipient_id)
      values (appt.id, 'incident_evidence', actor,
        case when actor = appt.client_id then appt.professional_id else appt.client_id end);
    return to_jsonb(result);
end;
$$;
revoke all on function public.add_incident_evidence(uuid, uuid, uuid, text, boolean) from public, anon;
grant execute on function public.add_incident_evidence(uuid, uuid, uuid, text, boolean) to authenticated;

-- A chat file becomes readable to the review only once contributed to an incident. Participants keep reading it
-- through the chat policy; the administrator gets no access to any other file of the conversation.
create function public.can_read_incident_evidence(p_path text)
returns boolean language sql stable security definer set search_path = '' as $$
    select exists (select 1 from public.incident_evidence e
        where e.attachment_path = p_path and public.can_read_incident(e.incident_id));
$$;
revoke all on function public.can_read_incident_evidence(text) from public, anon;
grant execute on function public.can_read_incident_evidence(text) to authenticated;
create policy incident_evidence_read on storage.objects for select to authenticated
    using (bucket_id = 'chat-attachments' and public.can_read_incident_evidence(name));
