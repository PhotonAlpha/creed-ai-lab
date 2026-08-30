-- Env Matrix Viewer — where a participant is drawn, as opposed to what it is connected to.
--
-- The topology graph ranks participants by a longest path over env_release_link, which is the right
-- default and is sometimes wrong: a promotion chain drawn as a straight line hides that two of its
-- slices are really the same step, and a participant with no declared upstream lands in column 0
-- whether or not that is where anyone would draw it. Until now those corrections lived in the
-- reader's browser (localStorage, per release), which meant nobody else ever saw them.
--
-- These two columns move that decision into the release, where the rest of the topology already is.

alter table env_release_node
    -- NULL means "no opinion — derive it from the links", which is the state every row starts in
    -- and the state a viewer returns a row to by clearing the field. A NOT NULL default of 0 would
    -- be a lie: it says "this participant belongs in column 0", so a later link that ought to push
    -- it right would silently disagree with a number nobody chose.
    add column layer      integer,
    -- Position along the cross axis, within a layer. Always has a value: 0 means "wherever the
    -- default ordering puts it" and the graph breaks ties by app system name, so a release nobody
    -- has reordered looks exactly as it did before this migration.
    add column sort_order integer not null default 0;

-- The graph draws one column (or row) per layer, and a layer of 400 would be 400 columns of empty
-- canvas between the graph and the one pinned box. The API rejects the same range with a per-row
-- issue; this is the backstop for anything that writes to the table directly.
alter table env_release_node
    add constraint ck_env_release_node_layer check (layer is null or (layer >= 0 and layer <= 99));
alter table env_release_node
    add constraint ck_env_release_node_sort_order check (sort_order >= -999 and sort_order <= 999);

-- Seed data keeps both defaults: every baseline release is drawn exactly as the links describe it.
