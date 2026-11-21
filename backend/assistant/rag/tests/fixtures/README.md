# 隔离演示数据

business-order-demo.json 是代码构造的5节点、4关系订单/检验示例；订单900001、行2、计划3、工单5、检验7均不是从MES导出的实际记录。仅使用 `--synthetic` 或维护脚本 `-Synthetic` 导入business_demo域。

它验证 Order→OrderItem→Plan→WorkOrder→Inspection 的结构和来源字段传递，不验证真实订单交付、质检合格率、库存或源MySQL事务。属性中的“合格”和数量只属于这个模拟例子，禁止用来回答真实业务问题。
