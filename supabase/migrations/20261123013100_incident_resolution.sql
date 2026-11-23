-- Administrative resolution of incidents (#31).
-- Two independent decisions, each with actor, date and a mandatory reason, executed once and never edited:
--   * Settlement of the simulated integrated balance still pending: deliver everything, refund everything, or refund
--     part and deliver the rest. Never more than the pending balance; refunds are recorded here and the delivery goes
--     through private.complete_appointment (#25), the one delivery point, which closes the job as 'administration'.
--     Only possible while the incident blocks the delivery and integrated money is pending: after the delivery, and
--     for direct or free appointments, there is no balance under the panel's control.
--   * Performance: performed | not_performed | missing_evidence. It decides the review (#28) and is never derived from
--     the money: a refund does not mean the service did not happen.
-- The incident is resolved once performance is decided and no pending money is left to settle.
-- Clock (#27): settling the money closes the job ('administration'); a job still marked done when the incident is
-- resolved is closed by the resolution too, so the clock never closes it afterwards as 'no_answer'. A started job
-- without money to settle stays started: «Realizado» later starts a new 48-hour window.
-- The administrator reads only the appointment's data, the accepted conditions, the movements and the evidence
-- contributed to that incident: never the exact place, the chat or files not contributed.

-- Shared with #28 (reviews), which may have created it already with the same definition.
do $$
begin
    if not exists (select 1 from information_schema.columns where table_schema = 'public' and table_name = 'incidents'
                   and column_name = 'performance_decision') then
        alter table public.incidents add column performance_decision text
            constraint incidents_performance_decision_check
            check (performance_decision in ('performed', 'not_performed', 'missing_evidence'));
    end if;
end;
$$;

alter table public.incidents
    add column performance_reason text check (length(performance_reason) between 1 and 2000),
    add column performance_decided_by uuid references auth.users(id),
    add column performance_decided_at timestamptz,
    add column settlement text constraint incidents_settlement_check check (settlement in ('deliver', 'refund', 'split')),
    add column settlement_refunded_cents integer check (settlement_refunded_cents >= 0),
    add column settlement_delivered_cents integer check (settlement_delivered_cents >= 0),
    add column settlement_reason text check (length(settlement_reason) between 1 and 2000),
    add column settled_by uuid references auth.users(id),
    add column settled_at timestamptz,
    add column resolved_at timestamptz,
    add constraint incidents_settlement_complete check ((settlement is null) = (settled_at is null)
        and (settlement is null) = (settlement_reason is null) and (settlement is null) = (settled_by is null)
        and (settlement is null) = (settlement_refunded_cents is null)
        and (settlement is null) = (settlement_delivered_cents is null));

-- Executed decisions are never edited, and a resolved incident is never reopened, even with trusted SQL.
create function private.keep_incident_resolution()
returns trigger language plpgsql set search_path = '' as $$
begin
    if (old.status = 'resolved' and new.status is distinct from 'resolved')
       or (old.resolved_at is not null and new.resolved_at is distinct from old.resolved_at)
       or (old.performance_decision is not null and (new.performance_decision, new.performance_reason,
           new.performance_decided_by, new.performance_decided_at) is distinct from (old.performance_decision,
           old.performance_reason, old.performance_decided_by, old.performance_decided_at))
       or (old.settlement is not null and (new.settlement, new.settlement_refunded_cents, new.settlement_delivered_cents,
           new.settlement_reason, new.settled_by, new.settled_at) is distinct from (old.settlement,
           old.settlement_refunded_cents, old.settlement_delivered_cents, old.settlement_reason, old.settled_by,
           old.settled_at)) then
        raise exception using errcode = '42501', message = 'incident_resolution_locked';
    end if;
    if new.status = 'resolved' and new.resolved_at is null then
        new.resolved_at := clock_timestamp();
    end if;
    return new;
end;
$$;
revoke all on function private.keep_incident_resolution() from public, anon, authenticated;
create trigger incidents_keep_resolution before update on public.incidents
    for each row execute function private.keep_incident_resolution();

-- 'incident_decision' is added to whatever kinds the earlier migrations allow, so parallel producers keep theirs.
do $$
declare
    definition text := (select pg_get_constraintdef(c.oid) from pg_constraint c
        where c.conrelid = 'public.appointment_events'::regclass and c.conname = 'appointment_events_kind_check');
begin
    if definition not like '%ARRAY[%' then
        raise exception 'unexpected appointment_events_kind_check: %', definition;
    end if;
    if definition not like '%''incident_decision''%' then
        execute 'alter table public.appointment_events drop constraint appointment_events_kind_check, add constraint '
            || 'appointment_events_kind_check ' || replace(definition, 'ARRAY[', 'ARRAY[''incident_decision''::text, ');
    end if;
end;
$$;

-- Closed by administration: possibly from 'started', so without the «Realizado» marks.
alter table public.appointments drop constraint appointments_completion_check,
    add constraint appointments_completion_check check (completion in ('client_confirmed', 'no_answer', 'administration')),
    drop constraint appointments_done_mark,
    add constraint appointments_done_mark check (stage not in ('done', 'completed')
        or (stage = 'completed' and completion = 'administration')
        or (done_at is not null and reminder_due_at is not null and close_due_at is not null));

-- A settled incident no longer blocks: its settlement is the delivery decision.
create or replace function private.delivery_blocked(p_appointment_id uuid)
returns boolean language sql stable security definer set search_path = ''
as $$ select exists (select 1 from public.incidents i
    where i.appointment_id = p_appointment_id and i.status = 'open' and i.blocks_delivery and i.settlement is null) $$;
revoke all on function private.delivery_blocked(uuid) from public, anon, authenticated;

-- Same invariant as #25, plus 'administration': an administrator (never a participant) closes a started or done job
-- after settling the incident. Everything still pending is delivered once; the refund was recorded before.
create or replace function private.complete_appointment(p_appointment_id uuid, p_actor uuid, p_completion text)
returns jsonb language plpgsql security definer set search_path = ''
as $$
declare
    appt public.appointments;
    payment public.appointment_payments;
begin
    select * into appt from public.appointments a where a.id = p_appointment_id for update;
    if appt.id is null or p_completion is null or p_completion not in ('client_confirmed', 'no_answer', 'administration')
       or (p_completion = 'client_confirmed' and p_actor is distinct from appt.client_id)
       or (p_completion = 'no_answer' and p_actor is not null)
       or (p_completion = 'administration' and (p_actor is null or p_actor in (appt.client_id, appt.professional_id)
           or not exists (select 1 from private.administrators ad where ad.user_id = p_actor))) then
        raise exception using errcode = '42501', message = 'permission_denied';
    end if;
    if appt.stage = 'completed' then
        return private.completion_marks(appt.id);
    end if;
    if appt.stage <> 'done' and not (p_completion = 'administration' and appt.stage = 'started') then
        raise exception using errcode = 'PT409', message = 'incompatible_state';
    end if;
    if private.delivery_blocked(appt.id) then
        raise exception using errcode = 'PT409', message = 'incident_blocking';
    end if;
    if p_completion = 'no_answer' and now() < appt.close_due_at then
        raise exception using errcode = 'PT409', message = 'not_due';
    end if;
    -- The accepted proposal holds the terms: a change still pending is closed without being applied.
    update public.appointment_changes set status = 'superseded', decided_at = now()
      where appointment_id = appt.id and status = 'pending';
    select * into payment from public.appointment_payments p where p.appointment_id = appt.id for update;
    if payment.mode = 'integrated' and payment.pending_cents > 0 then
        insert into public.payment_movements(appointment_id, kind, amount_cents, actor_id)
          values (appt.id, 'delivered', payment.pending_cents, p_actor);
        update public.appointment_payments set delivered_cents = delivered_cents + pending_cents, pending_cents = 0
          where appointment_id = appt.id;
    end if;
    update public.appointments set stage = 'completed', completed_at = now(), completion = p_completion,
        version = version + 1 where id = appt.id;
    if p_completion = 'client_confirmed' then
        insert into public.appointment_events(appointment_id, kind, actor_id, recipient_id)
          values (appt.id, 'completed', p_actor, appt.professional_id);
    else
        insert into public.appointment_events(appointment_id, kind, actor_id, recipient_id)
          values (appt.id, 'completed', p_actor, appt.client_id), (appt.id, 'completed', p_actor, appt.professional_id);
    end if;
    return private.completion_marks(appt.id);
end;
$$;
revoke all on function private.complete_appointment(uuid, uuid, text) from public, anon, authenticated;

-- Whether the panel controls a simulated balance for this incident now.
create function private.incident_settleable(p_incident public.incidents, p_appt public.appointments,
    p_payment public.appointment_payments)
returns boolean language sql stable set search_path = ''
as $$ select p_incident.status = 'open' and p_incident.blocks_delivery and p_incident.settlement is null
    and p_appt.stage in ('started', 'done') and p_payment.mode is not distinct from 'integrated'
    and coalesce(p_payment.pending_cents, 0) > 0 $$;
revoke all on function private.incident_settleable(public.incidents, public.appointments, public.appointment_payments)
    from public, anon, authenticated;

-- The review of one incident: what the administrator may see, and nothing else.
create function private.incident_review(p_incident_id uuid)
returns jsonb language sql stable security definer set search_path = ''
as $$
    select jsonb_build_object(
        'id', i.id, 'kind', i.kind, 'description', i.description, 'performance', i.performance, 'status', i.status,
        'created_at', i.created_at, 'resolved_at', i.resolved_at,
        'opened_by_role', case when i.opened_by = a.client_id then 'client' else 'professional' end,
        'opening', jsonb_build_object('appointment_stage', i.appointment_stage, 'payment_mode', i.payment_mode,
            'pending_cents', i.pending_cents, 'delivered_cents', i.delivered_cents, 'blocks_delivery', i.blocks_delivery),
        'can_settle', private.incident_settleable(i, a, p),
        'settlement', i.settlement, 'settlement_refunded_cents', i.settlement_refunded_cents,
        'settlement_delivered_cents', i.settlement_delivered_cents, 'settlement_reason', i.settlement_reason,
        'settled_at', i.settled_at, 'settled_by_email', (select u.email from auth.users u where u.id = i.settled_by),
        'performance_decision', i.performance_decision, 'performance_reason', i.performance_reason,
        'performance_decided_at', i.performance_decided_at,
        'performance_decided_by_email', (select u.email from auth.users u where u.id = i.performance_decided_by),
        'appointment', jsonb_build_object('id', a.id, 'stage', a.stage, 'problem', a.problem,
            'profile_name', (select ap.name from public.approved_profiles ap where ap.profile_id = a.profile_id),
            'requested_start', a.requested_start, 'requested_end', a.requested_end, 'started_at', a.started_at,
            'done_at', a.done_at, 'close_due_at', a.close_due_at, 'completed_at', a.completed_at,
            'completion', a.completion),
        'conditions', (select jsonb_build_object('version', c.version, 'scope', c.scope, 'start_at', c.start_at,
            'end_at', c.end_at, 'duration_minutes', c.duration_minutes, 'amount_cents', c.amount_cents,
            'visit_fee_cents', c.visit_fee_cents, 'materials_included', c.materials_included,
            'travel_included', c.travel_included)
            from public.appointment_proposals c where c.appointment_id = a.id and c.status = 'accepted'),
        'payment', case when p.appointment_id is null then null else jsonb_build_object('mode', p.mode,
            'agreed_cents', p.agreed_cents, 'paid_cents', p.paid_cents, 'pending_cents', p.pending_cents,
            'refunded_cents', p.refunded_cents, 'delivered_cents', p.delivered_cents) end,
        'movements', coalesce((select jsonb_agg(jsonb_build_object('kind', m.kind, 'amount_cents', m.amount_cents,
            'created_at', m.created_at, 'by', case when m.actor_id is null then 'system'
                when m.actor_id = a.client_id then 'client' when m.actor_id = a.professional_id then 'professional'
                else 'administration' end) order by m.created_at, m.kind)
            from public.payment_movements m where m.appointment_id = a.id), '[]'::jsonb),
        'evidence', coalesce((select jsonb_agg(jsonb_build_object('id', e.id, 'kind', e.kind, 'note', e.note,
            'author_role', case when e.author_id = a.client_id then 'client' else 'professional' end,
            'message_author_role', case when e.message_author_id is null then null
                when e.message_author_id = a.client_id then 'client' else 'professional' end,
            'message_body', e.message_body, 'message_created_at', e.message_created_at,
            'attachment_kind', e.attachment_kind, 'attachment_path', e.attachment_path,
            'attachment_type', e.attachment_type, 'created_at', e.created_at) order by e.created_at)
            from public.incident_evidence e where e.incident_id = i.id), '[]'::jsonb))
    from public.incidents i
    join public.appointments a on a.id = i.appointment_id
    left join public.appointment_payments p on p.appointment_id = a.id
    where i.id = p_incident_id
$$;
revoke all on function private.incident_review(uuid) from public, anon, authenticated;

-- Panel list. p_status: 'open' (default), 'resolved' or 'all'. Incidents of the administrator's own appointments are
-- omitted, like own profiles in the profile review.
create function public.admin_incidents(p_status text default 'open')
returns jsonb language plpgsql stable security definer set search_path = '' as $$
declare actor uuid := private.require_administrator();
begin
    if p_status is null or p_status not in ('open', 'resolved', 'all') then
        raise exception using errcode = '22023', message = 'invalid_status';
    end if;
    return coalesce((select jsonb_agg(private.incident_review(x.id) order by x.created_at desc) from (
        select i.id, i.created_at from public.incidents i join public.appointments a on a.id = i.appointment_id
        where (p_status = 'all' or i.status = p_status) and actor not in (a.client_id, a.professional_id)
        order by i.created_at desc limit 200) x), '[]'::jsonb);
end;
$$;

-- Shared start of both decisions: administrator, reason, lock order (appointment row, then payment) like every other
-- writer of the appointment. Returns the locked rows.
create function private.lock_incident_for_decision(p_incident_id uuid, p_reason text, out actor uuid,
    out incident public.incidents, out appt public.appointments, out payment public.appointment_payments)
language plpgsql security definer set search_path = '' as $$
declare v_appointment uuid;
begin
    actor := private.require_administrator();
    if p_reason is null or length(btrim(p_reason)) not between 1 and 2000 then
        raise exception using errcode = '22023', message = 'reason_required';
    end if;
    select i.appointment_id into v_appointment from public.incidents i where i.id = p_incident_id;
    if v_appointment is null then
        raise exception using errcode = '22023', message = 'incident_not_found';
    end if;
    select * into appt from public.appointments a where a.id = v_appointment for update;
    -- Administration never decides on its own appointment, even with a dual-role account.
    if actor in (appt.client_id, appt.professional_id) then
        raise exception using errcode = '42501', message = 'permission_denied';
    end if;
    select * into incident from public.incidents i where i.id = p_incident_id;
    select * into payment from public.appointment_payments p where p.appointment_id = appt.id for update;
end;
$$;
revoke all on function private.lock_incident_for_decision(uuid, text) from public, anon, authenticated;

-- After a decision: resolve when performance is decided and no pending money is left to settle, and tell both
-- participants about the decision (one 'incident_decision' notice each).
create function private.after_incident_decision(p_incident_id uuid, p_actor uuid)
returns jsonb language plpgsql security definer set search_path = '' as $$
declare
    incident public.incidents;
    appt public.appointments;
    payment public.appointment_payments;
begin
    select * into incident from public.incidents i where i.id = p_incident_id;
    select * into appt from public.appointments a where a.id = incident.appointment_id;
    select * into payment from public.appointment_payments p where p.appointment_id = appt.id;
    if incident.performance_decision is not null and not private.incident_settleable(incident, appt, payment) then
        update public.incidents set status = 'resolved' where id = incident.id;
        -- A job marked done waited only for this review: the resolution closes it now (nothing pending is left to
        -- deliver), instead of the clock (#27) closing it later as 'no_answer' when the client did answer.
        if appt.stage = 'done' then
            perform private.complete_appointment(appt.id, p_actor, 'administration');
        end if;
    end if;
    insert into public.appointment_events(appointment_id, kind, actor_id, recipient_id)
      values (appt.id, 'incident_decision', p_actor, appt.client_id),
             (appt.id, 'incident_decision', p_actor, appt.professional_id);
    select * into incident from public.incidents i where i.id = p_incident_id;
    return to_jsonb(incident);
end;
$$;
revoke all on function private.after_incident_decision(uuid, uuid) from public, anon, authenticated;

-- p_outcome: 'deliver' (everything to the professional), 'refund' (everything to the client) or 'split'
-- (p_refund_cents to the client, the rest to the professional). p_expected_pending_cents is the balance the
-- administrator reviewed: if it changed meanwhile nothing is executed. Repeating the same decision returns it.
create function public.admin_settle_incident(p_incident_id uuid, p_outcome text, p_refund_cents integer,
    p_expected_pending_cents integer, p_reason text)
returns jsonb language plpgsql security definer set search_path = '' as $$
declare
    locked record;
    v_reason text := btrim(p_reason);
    refund integer;
begin
    select * into locked from private.lock_incident_for_decision(p_incident_id, p_reason);
    if p_outcome is null or p_outcome not in ('deliver', 'refund', 'split') then
        raise exception using errcode = '22023', message = 'invalid_settlement';
    end if;
    if (locked.incident).settlement is not null then
        if (locked.incident).settlement = p_outcome and (locked.incident).settled_by = locked.actor
           and (locked.incident).settlement_reason = v_reason
           and (p_outcome <> 'split' or (locked.incident).settlement_refunded_cents = p_refund_cents) then
            return to_jsonb(locked.incident);
        end if;
        raise exception using errcode = 'PT409', message = 'decision_already_recorded';
    end if;
    if (locked.incident).status <> 'open' then
        raise exception using errcode = 'PT409', message = 'incident_closed';
    end if;
    if not private.incident_settleable(locked.incident, locked.appt, locked.payment) then
        raise exception using errcode = 'PT409', message = 'nothing_pending';
    end if;
    refund := case p_outcome when 'deliver' then 0 when 'refund' then (locked.payment).pending_cents else p_refund_cents end;
    if p_outcome = 'split' and (refund is null or refund <= 0 or refund >= (locked.payment).pending_cents) then
        raise exception using errcode = '22023', message = 'invalid_settlement';
    end if;
    if p_expected_pending_cents is distinct from (locked.payment).pending_cents then
        raise exception using errcode = 'PT409', message = 'balance_changed';
    end if;
    update public.incidents set settlement = p_outcome, settlement_refunded_cents = refund,
        settlement_delivered_cents = (locked.payment).pending_cents - refund, settlement_reason = v_reason,
        settled_by = locked.actor, settled_at = clock_timestamp()
      where id = (locked.incident).id;
    if refund > 0 then
        insert into public.payment_movements(appointment_id, kind, amount_cents, actor_id)
          values ((locked.appt).id, 'refunded', refund, locked.actor);
        update public.appointment_payments set pending_cents = pending_cents - refund, refunded_cents = refunded_cents + refund
          where appointment_id = (locked.appt).id;
    end if;
    perform private.complete_appointment((locked.appt).id, locked.actor, 'administration');
    return private.after_incident_decision((locked.incident).id, locked.actor);
end;
$$;

-- p_decision: 'performed' | 'not_performed' | 'missing_evidence'. Independent from the settlement.
create function public.admin_decide_incident_performance(p_incident_id uuid, p_decision text, p_reason text)
returns jsonb language plpgsql security definer set search_path = '' as $$
declare
    locked record;
    v_reason text := btrim(p_reason);
begin
    select * into locked from private.lock_incident_for_decision(p_incident_id, p_reason);
    if p_decision is null or p_decision not in ('performed', 'not_performed', 'missing_evidence') then
        raise exception using errcode = '22023', message = 'invalid_decision';
    end if;
    if (locked.incident).performance_decision is not null then
        if (locked.incident).performance_decision = p_decision and (locked.incident).performance_decided_by = locked.actor
           and (locked.incident).performance_reason = v_reason then
            return to_jsonb(locked.incident);
        end if;
        raise exception using errcode = 'PT409', message = 'decision_already_recorded';
    end if;
    if (locked.incident).status <> 'open' then
        raise exception using errcode = 'PT409', message = 'incident_closed';
    end if;
    update public.incidents set performance_decision = p_decision, performance_reason = v_reason,
        performance_decided_by = locked.actor, performance_decided_at = clock_timestamp()
      where id = (locked.incident).id;
    return private.after_incident_decision((locked.incident).id, locked.actor);
end;
$$;

revoke all on function public.admin_incidents(text), public.admin_settle_incident(uuid, text, integer, integer, text),
    public.admin_decide_incident_performance(uuid, text, text) from public, anon;
grant execute on function public.admin_incidents(text), public.admin_settle_incident(uuid, text, integer, integer, text),
    public.admin_decide_incident_performance(uuid, text, text) to authenticated;
