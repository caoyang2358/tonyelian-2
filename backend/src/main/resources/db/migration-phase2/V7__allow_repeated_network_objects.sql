-- A diagram can show the same device or sensor at multiple points.
-- The node primary key and the object foreign-key indexes still enforce identity and references.
ALTER TABLE p2_network_node
    DROP INDEX uk_p2_layout_equipment,
    DROP INDEX uk_p2_layout_sensor;
