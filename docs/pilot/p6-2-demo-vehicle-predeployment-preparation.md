# V2 演示车辆部署前数据准备

## 业务确认与当前权限

2026-09-10，业务负责人通过用户确认：**DRT-001/DRT-002 为演示数据，不参与通渭县试点运营**。

本次仅做本地准备和源码只读核对。未连接云端，未修改车辆属性、manifest、业务代码或迁移文件，未执行SQL。下述目标不是V19–V21迁移脚本的一部分，也不纳入Flyway自动执行。

| 车辆ID | 车牌 | 车队 | 预期旧值 | 目标值 |
| --- | --- | --- | --- | --- |
| 33333333-3333-3333-3333-333333333331 | DRT-001 | Demo Fleet | dispatchable=true | dispatchable=false |
| 33333333-3333-3333-3333-333333333332 | DRT-002 | Demo Fleet | dispatchable=true | dispatchable=false |

车牌和车队由本次业务确认给出，ID与V2种子及上一轮云端只读结果匹配；执行前仍需重新核实云端现值。历史盘点两条记录均IDLE、无终端绑定，不能视为现在未经查询的事实。

## 待授权的受控调整合同

1. 确认主机、数据库、Flyway当前版本及操作者身份，取得部署前完整备份和可恢复性证据。仅查到旧备份文件不算满足此条件。
2. 在只读事务中按下列精确ID查询，要求恰好两条，车牌/车队逐条匹配业务确认。检查状态和有效绑定；任何与历史盘点不一致之处先停止复核。
3. 本地VehicleController仅含GET列表和POST创建，未发现已验证的更新dispatchable接口。不得把创建接口用于更新，不删除重建车辆。后续需单独授权受控数据库事务或另行已验证的管理入口，本次不运行二者。
4. 若后续采用数据库事务：设置锁等待及语句超时；重新锁定并核验两条目标记录及有效绑定，阻止检查后绑定并发变化；同时匹配ID、车牌、Demo Fleet和旧dispatchable=true，仅将dispatchable改false，预期影响恰好两条。任何不匹配、非预期影响数、超时或审计失败均回滚，不自动重试或扩大WHERE范围。
5. 不删除车辆，不修改车牌/车队/状态/位置/关联历史，不修改其他车辆，不修改manifest，不更新V2/V19/V20/V21文件。若两条已为false，先标记已达目标并核对历史证据，不重复写入。
6. 保留操作者、授权依据、时间、目标ID、修改前后值、事务结果和备份校验值。不能声称直接SQL自动产生业务audit_logs；执行前明确满足适用审计要求的记录机制。
7. 在同一事务内复核两条结果、非目标车辆dispatchable未变及目标有效绑定无新增，满足全部条件后才按独立授权提交。提交后另开只读事务复核。恢复旧值也需要新的业务确认和授权，不能自动重新纳入调度。

## 只读复核SQL（仅准备，尚未执行）

所有查询在同一只读会话、短超时内执行；任何错误即停。V18可先核对车辆属性，不引用尚不存在的onboard_systems。

```sql
BEGIN READ ONLY;
SET LOCAL statement_timeout = '5s';
SET LOCAL lock_timeout = '2s';

SELECT id, plate_number, fleet_name, current_status, dispatchable
FROM vehicles
WHERE id IN (
  '33333333-3333-3333-3333-333333333331'::uuid,
  '33333333-3333-3333-3333-333333333332'::uuid
)
ORDER BY id;

SELECT count(*) AS active_target_bindings
FROM jt_terminal_vehicle_bindings
WHERE vehicle_id IN (
  '33333333-3333-3333-3333-333333333331'::uuid,
  '33333333-3333-3333-3333-333333333332'::uuid
)
AND status = 'ACTIVE' AND valid_to IS NULL;

-- 这是全库计数，不只检查目标ID或manifest中的车辆。
SELECT count(*) AS dispatchable_vehicle_count
FROM vehicles WHERE dispatchable = true;

SELECT to_regclass('public.onboard_systems') AS onboard_systems_table;
ROLLBACK;
```

目标调整后的验收：两条目标仍存在且属性false；在其余数据不变的前提下，预计全库dispatchable=true计数为0。**这只是预期，不是本次实查结论。** 若计数非0，继续只读盘点其余车辆，不扩大本次修改范围。

onboard_systems不存在时不执行下一段，也不为检查而创建表。只有V19另获授权并执行后，才可使用与V20一致的查询：

```sql
BEGIN READ ONLY;
SET LOCAL statement_timeout = '5s';
SET LOCAL lock_timeout = '2s';
SELECT count(*) AS dispatchable_without_active_system
FROM vehicles v
WHERE v.dispatchable = true
  AND NOT EXISTS (
    SELECT 1 FROM onboard_systems s
    WHERE s.vehicle_id = v.id AND s.status = 'ACTIVE'
  );
ROLLBACK;
```

该值必须为0。它只关闭DISPATCH_SYSTEM_MISSING一项，不代表V20全部通过：还需全库核对ACTIVE终端成员关系、调度模式及角色、角色与VERIFIED能力、WAN网络模式、定位主备分离、ACTIVE系统至少一成员等。V19按有效旧绑定回填；无绑定的两辆演示车改false后仍不会自动创建系统，但不再触发此调度系统必需条件。

## 当前状态与下一步

- BUSINESS_CONFIRMATION=RECORDED
- LOCAL_PREPARATION=READY
- CLOUD_ATTRIBUTE_UPDATE=NOT_EXECUTED
- CLOUD_GLOBAL_GATE_RECHECK=NOT_EXECUTED
- V19_V20_V21=PAUSED

等待云端只读复核及两条属性调整的明确授权，再执行准备；完成后仍需另行授权迁移。云端备份恢复和其余全库门禁保持独立。
