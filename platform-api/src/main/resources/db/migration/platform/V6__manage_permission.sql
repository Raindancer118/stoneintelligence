-- MANAGE (Mitglieder, Gruppen, Rollen, Pfadregeln, Einladungen verwalten) ist ab jetzt ein eigenes
-- Recht. Bisher stand DELETE stellvertretend dafuer - jede Rolle, die bislang DELETE hatte, konnte
-- damit auch verwalten und behaelt das: sie bekommt MANAGE dazu. Keine andere Rolle aendert sich.
INSERT INTO platform.role_permissions (role_id, permission)
SELECT role_id, 'MANAGE' FROM platform.role_permissions WHERE permission = 'DELETE'
ON CONFLICT DO NOTHING;
