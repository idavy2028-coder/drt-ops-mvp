-- 任务插单会在同一事务内重排多个站点。Hibernate 可能先插入新节点，
-- 再更新原节点序号，因此唯一性应在事务最终状态稳定后检查。
ALTER TABLE task_stops
  DROP CONSTRAINT task_stops_vehicle_task_id_sequence_number_key;

ALTER TABLE task_stops
  ADD CONSTRAINT task_stops_vehicle_task_id_sequence_number_key
  UNIQUE (vehicle_task_id, sequence_number)
  DEFERRABLE INITIALLY DEFERRED;
