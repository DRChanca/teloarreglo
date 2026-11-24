-- Chat videos (#19). Reuses the #18 boundary unchanged: same private bucket,
-- reservation table, RPCs and Storage policies. Only enables ('video','video/mp4')
-- at the single extension point and widens the bucket so a 20 MiB MP4 fits.
-- The per-kind limit is still enforced at confirmation: a photo over 2 MiB that
-- Storage now accepts never becomes a message. Duration and resolution (1 min,
-- 720p) are enforced by the app's compression; the server cannot inspect them.
create or replace function private.chat_attachment_limit(p_kind text, p_mime text)
returns bigint language sql immutable set search_path = '' as $$
    select case
        when p_kind = 'photo' and p_mime in ('image/jpeg', 'image/png') then 2097152::bigint
        when p_kind = 'video' and p_mime = 'video/mp4' then 20971520::bigint
    end;
$$;
revoke all on function private.chat_attachment_limit(text, text) from public, anon, authenticated;

update storage.buckets
    set file_size_limit = 20971520, allowed_mime_types = array['image/jpeg', 'image/png', 'video/mp4']
    where id = 'chat-attachments';
