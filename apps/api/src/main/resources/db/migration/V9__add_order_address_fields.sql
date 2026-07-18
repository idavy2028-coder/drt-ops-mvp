ALTER TABLE ride_orders
    ADD COLUMN origin_address VARCHAR(300),
    ADD COLUMN destination_address VARCHAR(300),
    ADD COLUMN coordinate_system VARCHAR(20) NOT NULL DEFAULT 'GCJ02',
    ADD COLUMN origin_address_source VARCHAR(40) NOT NULL DEFAULT 'MANUAL_COORDINATE',
    ADD COLUMN destination_address_source VARCHAR(40) NOT NULL DEFAULT 'MANUAL_COORDINATE';

UPDATE ride_orders
   SET origin_address = CONCAT('坐标定位 ', origin_lng, ',', origin_lat),
       destination_address = CONCAT('坐标定位 ', destination_lng, ',', destination_lat)
 WHERE origin_address IS NULL OR destination_address IS NULL;

ALTER TABLE ride_orders
    ALTER COLUMN origin_address SET NOT NULL,
    ALTER COLUMN destination_address SET NOT NULL;
