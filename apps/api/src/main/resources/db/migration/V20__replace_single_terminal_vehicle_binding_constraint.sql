LOCK TABLE vehicles IN SHARE ROW EXCLUSIVE MODE;
LOCK TABLE jt_terminals IN SHARE ROW EXCLUSIVE MODE;
LOCK TABLE onboard_systems IN SHARE ROW EXCLUSIVE MODE;
LOCK TABLE onboard_system_runtime_state IN SHARE ROW EXCLUSIVE MODE;
LOCK TABLE onboard_device_memberships IN SHARE ROW EXCLUSIVE MODE;
LOCK TABLE onboard_device_role_assignments IN SHARE ROW EXCLUSIVE MODE;
LOCK TABLE onboard_device_capabilities IN SHARE ROW EXCLUSIVE MODE;
LOCK TABLE onboard_device_protocol_profiles IN SHARE ROW EXCLUSIVE MODE;
LOCK TABLE jt_terminal_vehicle_bindings IN SHARE ROW EXCLUSIVE MODE;

DO $$
BEGIN
  IF EXISTS (
    SELECT 1
    FROM jt_terminals terminal
    WHERE terminal.status = 'ACTIVE'
      AND 1 <> (
        SELECT count(*)
        FROM onboard_device_memberships membership
        JOIN onboard_systems system
          ON system.id = membership.onboard_system_id
        WHERE membership.terminal_id = terminal.id
          AND membership.status = 'ACTIVE'
          AND membership.valid_to IS NULL
          AND system.status = 'ACTIVE'
      )
  ) THEN
    RAISE EXCEPTION USING
      MESSAGE = 'ONBOARD_CONTRACT_ACTIVE_TERMINAL_MEMBERSHIP_MISSING',
      ERRCODE = '55000';
  END IF;
END
$$;

DO $$
BEGIN
  IF EXISTS (
    SELECT 1
    FROM vehicles vehicle
    WHERE vehicle.dispatchable
      AND NOT EXISTS (
        SELECT 1
        FROM onboard_systems system
        WHERE system.vehicle_id = vehicle.id
          AND system.status = 'ACTIVE'
      )
  ) THEN
    RAISE EXCEPTION USING
      MESSAGE = 'ONBOARD_CONTRACT_DISPATCH_SYSTEM_MISSING',
      ERRCODE = '55000';
  END IF;
END
$$;

DO $$
BEGIN
  IF EXISTS (
    SELECT 1
    FROM vehicles vehicle
    JOIN onboard_systems system
      ON system.vehicle_id = vehicle.id
     AND system.status = 'ACTIVE'
    WHERE vehicle.dispatchable
      AND system.operating_mode <> 'DISPATCH_SERVICE'
  ) THEN
    RAISE EXCEPTION USING
      MESSAGE = 'ONBOARD_CONTRACT_DISPATCH_MODE_INVALID',
      ERRCODE = '55000';
  END IF;
END
$$;

DO $$
BEGIN
  IF EXISTS (
    SELECT 1
    FROM vehicles vehicle
    JOIN onboard_systems system
      ON system.vehicle_id = vehicle.id
     AND system.status = 'ACTIVE'
    WHERE vehicle.dispatchable
      AND 1 <> (
        SELECT count(*)
        FROM onboard_device_role_assignments assignment
        WHERE assignment.onboard_system_id = system.id
          AND assignment.role = 'DISPATCH'
          AND assignment.status = 'ACTIVE'
          AND assignment.valid_to IS NULL
      )
  ) THEN
    RAISE EXCEPTION USING
      MESSAGE = 'ONBOARD_CONTRACT_DISPATCH_ROLE_MISSING',
      ERRCODE = '55000';
  END IF;
END
$$;

DO $$
BEGIN
  IF EXISTS (
    SELECT 1
    FROM vehicles vehicle
    JOIN onboard_systems system
      ON system.vehicle_id = vehicle.id
     AND system.status = 'ACTIVE'
    WHERE vehicle.dispatchable
      AND 1 <> (
        SELECT count(*)
        FROM onboard_device_role_assignments assignment
        WHERE assignment.onboard_system_id = system.id
          AND assignment.role = 'LOCATION_PRIMARY'
          AND assignment.status = 'ACTIVE'
          AND assignment.valid_to IS NULL
      )
  ) THEN
    RAISE EXCEPTION USING
      MESSAGE = 'ONBOARD_CONTRACT_LOCATION_PRIMARY_ROLE_MISSING',
      ERRCODE = '55000';
  END IF;
END
$$;

DO $$
BEGIN
  IF EXISTS (
    SELECT 1
    FROM onboard_device_role_assignments assignment
    WHERE assignment.status = 'ACTIVE'
      AND assignment.valid_to IS NULL
      AND NOT EXISTS (
        SELECT 1
        FROM onboard_device_memberships membership
        WHERE membership.onboard_system_id = assignment.onboard_system_id
          AND membership.terminal_id = assignment.terminal_id
          AND membership.status = 'ACTIVE'
          AND membership.valid_to IS NULL
      )
  ) THEN
    RAISE EXCEPTION USING
      MESSAGE = 'ONBOARD_CONTRACT_ROLE_MEMBERSHIP_MISSING',
      ERRCODE = '55000';
  END IF;
END
$$;

DO $$
BEGIN
  IF EXISTS (
    SELECT 1
    FROM onboard_device_role_assignments assignment
    WHERE assignment.status = 'ACTIVE'
      AND assignment.valid_to IS NULL
      AND assignment.role <> 'WAN_UPLINK'
      AND NOT EXISTS (
        SELECT 1
        FROM onboard_device_capabilities capability
        WHERE capability.terminal_id = assignment.terminal_id
          AND capability.status = 'VERIFIED'
          AND (
            assignment.role = 'DISPATCH'
              AND capability.capability IN ('GBT28787_DISPATCH', 'VENDOR_DISPATCH')
            OR assignment.role IN ('LOCATION_PRIMARY', 'LOCATION_BACKUP')
              AND capability.capability = 'JT808_LOCATION'
            OR assignment.role = 'ACTIVE_SAFETY'
              AND capability.capability IN ('ADAS', 'DMS')
            OR assignment.role = 'VIDEO'
              AND capability.capability = 'VIDEO'
          )
      )
  ) THEN
    RAISE EXCEPTION USING
      MESSAGE = 'ONBOARD_CONTRACT_ROLE_CAPABILITY_MISSING',
      ERRCODE = '55000';
  END IF;
END
$$;

DO $$
BEGIN
  IF EXISTS (
    SELECT 1
    FROM onboard_device_role_assignments assignment
    JOIN onboard_device_memberships membership
      ON membership.onboard_system_id = assignment.onboard_system_id
     AND membership.terminal_id = assignment.terminal_id
     AND membership.status = 'ACTIVE'
     AND membership.valid_to IS NULL
    WHERE assignment.status = 'ACTIVE'
      AND assignment.valid_to IS NULL
      AND assignment.role = 'WAN_UPLINK'
      AND membership.network_mode <> 'DIRECT_CELLULAR'
  ) THEN
    RAISE EXCEPTION USING
      MESSAGE = 'ONBOARD_CONTRACT_WAN_UPLINK_NETWORK_MODE_INVALID',
      ERRCODE = '55000';
  END IF;
END
$$;

DO $$
BEGIN
  IF EXISTS (
    SELECT 1
    FROM onboard_device_role_assignments primary_role
    JOIN onboard_device_role_assignments backup_role
      ON backup_role.onboard_system_id = primary_role.onboard_system_id
     AND backup_role.role = 'LOCATION_BACKUP'
     AND backup_role.status = 'ACTIVE'
     AND backup_role.valid_to IS NULL
    WHERE primary_role.role = 'LOCATION_PRIMARY'
      AND primary_role.status = 'ACTIVE'
      AND primary_role.valid_to IS NULL
      AND primary_role.terminal_id = backup_role.terminal_id
  ) THEN
    RAISE EXCEPTION USING
      MESSAGE = 'ONBOARD_CONTRACT_LOCATION_TERMINALS_NOT_DISTINCT',
      ERRCODE = '55000';
  END IF;
END
$$;

DO $$
BEGIN
  IF EXISTS (
    SELECT 1
    FROM onboard_systems system
    WHERE system.status = 'ACTIVE'
      AND NOT EXISTS (
        SELECT 1
        FROM onboard_device_memberships membership
        WHERE membership.onboard_system_id = system.id
          AND membership.status = 'ACTIVE'
          AND membership.valid_to IS NULL
      )
  ) THEN
    RAISE EXCEPTION USING
      MESSAGE = 'ONBOARD_CONTRACT_ACTIVE_SYSTEM_MEMBERSHIP_MISSING',
      ERRCODE = '55000';
  END IF;
END
$$;

DROP INDEX uq_jt_terminal_vehicle_bindings_active_vehicle;

CREATE FUNCTION reject_legacy_terminal_binding_write()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
  RAISE EXCEPTION USING
    MESSAGE = 'LEGACY_TERMINAL_BINDINGS_READ_ONLY',
    ERRCODE = '55000';
END
$$;

CREATE TRIGGER trg_jt_terminal_vehicle_bindings_read_only
BEFORE INSERT OR UPDATE OR DELETE ON jt_terminal_vehicle_bindings
FOR EACH STATEMENT
EXECUTE FUNCTION reject_legacy_terminal_binding_write();
