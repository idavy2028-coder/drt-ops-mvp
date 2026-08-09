# 地图点选空地址保留 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 地图点选时不再向空地址字段写入“地图点选位置”。

**Architecture:** 变更限定在地址坐标组件的地图点击回调。组件继续负责回写地址、坐标和虚拟站点状态；地址值只取当前输入框的修剪结果，不引入新的 API 或后端逻辑。

**Tech Stack:** Vue 3、TypeScript、Vitest、Vue Testing Library。

## Global Constraints

- 不改变手工输入地址、坐标标准化或虚拟站点清除行为。
- 不自动使用推荐站点名称填充地址。
- 不修改订单 API 或数据库地址回退逻辑。

---

### Task 1: 地图点选地址回写

**Files:**
- Modify: `apps/admin-web/src/components/address-coordinate-field.test.ts:72-87`
- Modify: `apps/admin-web/src/components/AddressCoordinateField.vue:89-96`

**Interfaces:**
- Consumes: `tileMap.onClick((point) => void)` 提供的 `{ longitude, latitude }`。
- Produces: `update:modelValue` 事件，值为 `AddressCoordinateValue`，其 `address` 保留输入框修剪后的文本。

- [ ] **Step 1: 写入失败测试**

将“空地址地图点选”的断言改为：

```ts
expect(changes?.[changes.length - 1]?.[0]).toMatchObject({
  address: "",
  longitude: 105.245,
  latitude: 35.215,
  virtualStopId: undefined
});
```

- [ ] **Step 2: 运行测试确认失败**

运行：

```powershell
npm.cmd test -- --run src/components/address-coordinate-field.test.ts
```

预期：测试失败，实际地址仍为“地图点选位置”。

- [ ] **Step 3: 实施最小修复**

将地图点击回调中的地址赋值改为：

```ts
address: keyword.value.trim(),
```

- [ ] **Step 4: 运行组件测试与类型检查**

运行：

```powershell
npm.cmd test -- --run src/components/address-coordinate-field.test.ts
npm.cmd run typecheck
```

预期：两项均通过。

- [ ] **Step 5: 浏览器验收**

在本地订单页新开录入需求，不输入地址直接地图点选。确认地址框保持空白、坐标仍被保存，且不提交订单；随后录入手工地址并点选，确认手工地址仍保留。

- [ ] **Step 6: 提交**

```powershell
git add docs/superpowers/specs/2026-08-03-map-picker-empty-address-design.md docs/superpowers/plans/2026-08-03-map-picker-empty-address.md apps/admin-web/src/components/address-coordinate-field.test.ts apps/admin-web/src/components/AddressCoordinateField.vue
git commit -m "fix: preserve empty address after map selection"
```
