package com.upc.computer.service;

import com.upc.computer.entity.DispatchTask;
import com.upc.computer.entity.Equipment;
import com.upc.computer.entity.ProcessStep;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ProductionWorkshopCatalogTest {
    @Test
    void catalogHasEightOrderedStagesAndNineteenUniqueWorkshops() {
        assertEquals(8, ProductionWorkshopCatalog.PRODUCTION_STAGES.size());
        assertEquals(19, ProductionWorkshopCatalog.allWorkshops().size());
        assertEquals(19, ProductionWorkshopCatalog.allWorkshops().stream().map(w -> w.key()).distinct().count());
        for (var stage : ProductionWorkshopCatalog.PRODUCTION_STAGES) {
            assertSame(stage, ProductionWorkshopCatalog.stageByKey(stage.stepKey()));
            assertEquals(stage.workshops().size(), stage.workshopCount());
            for (var workshop : stage.workshops()) {
                assertEquals(workshop, ProductionWorkshopCatalog.workshopByKey(workshop.key()));
                assertEquals(stage.stepOrder(), workshop.stepOrder());
            }
        }
        assertNull(ProductionWorkshopCatalog.stageByKey("unknown"));
        assertNull(ProductionWorkshopCatalog.workshopByKey(null));
    }

    @Test
    void recognizesExactNameKeywordEquipmentAndNonProductionSteps() {
        assertNull(ProductionWorkshopCatalog.stageForStep(null));
        for (String name : new String[]{null, "", "  ", "主板质检", "包装"}) {
            assertFalse(ProductionWorkshopCatalog.isProductionStep(step(1L, name)));
        }
        assertEquals("motherboard", ProductionWorkshopCatalog.stageForStep(step(1L, "主板装配")).stepKey());
        assertEquals("motherboard", ProductionWorkshopCatalog.stageForStep(step(1L, "主板焊接")).stepKey());
        ProcessStep byEquipment = step(1L, "特殊装配");
        byEquipment.setStandardEquipmentType(" 主板线 ");
        assertEquals("motherboard", ProductionWorkshopCatalog.stageForStep(byEquipment).stepKey());
        assertNull(ProductionWorkshopCatalog.stageForStep(step(1L, "未知工序")));
        assertFalse(ProductionWorkshopCatalog.matchesStage(byEquipment, null));
        assertFalse(ProductionWorkshopCatalog.matchesStage(null, ProductionWorkshopCatalog.stageByKey("motherboard")));
        assertTrue(ProductionWorkshopCatalog.isFinalProductionStep(step(8L, "支架底座装配")));
        assertFalse(ProductionWorkshopCatalog.isFinalProductionStep(step(1L, "主板装配")));
        assertFalse(ProductionWorkshopCatalog.isFinalProductionStep(null));
    }

    @Test
    void selectsEnabledRouteStepsInProductionOrderAndChoosesEarliestDuplicate() {
        ProcessStep first = step(1L, "主板装配");
        ProcessStep duplicate = step(2L, "主板装配");
        duplicate.setStepNo(8);
        ProcessStep disabled = step(3L, "电源板装配");
        disabled.setStatus(0);
        ProcessStep otherRoute = step(4L, "接口板装配");
        otherRoute.setRouteId(2L);
        ProcessStep last = step(8L, "支架底座装配");
        last.setStatus(null);
        assertEquals(List.of(first, last), ProductionWorkshopCatalog.resolveProductionStepsForRoute(
                1L, List.of(last, duplicate, disabled, otherRoute, first)));
        assertTrue(ProductionWorkshopCatalog.resolveProductionStepsForRoute(null, List.of(first)).isEmpty());
        assertTrue(ProductionWorkshopCatalog.resolveProductionStepsForRoute(1L, null).isEmpty());
        assertTrue(ProductionWorkshopCatalog.resolveProductionStepsForRoute(1L, List.of()).isEmpty());
    }

    @Test
    void matchesWorkshopByExplicitLocationOrFallbackEquipmentType() {
        var workshop = ProductionWorkshopCatalog.workshopByKey("mb-1");
        ProcessStep mainboard = step(1L, "主板装配");
        assertEquals(workshop, ProductionWorkshopCatalog.workshopForStep(mainboard));
        assertNull(ProductionWorkshopCatalog.workshopForStep(null));
        assertTrue(ProductionWorkshopCatalog.matchesWorkshop(mainboard, workshop));
        assertFalse(ProductionWorkshopCatalog.matchesWorkshop(null, workshop));
        Equipment equipment = new Equipment();
        equipment.setEquipmentType(" 主板线 ");
        assertTrue(ProductionWorkshopCatalog.equipmentBelongsToWorkshop(equipment, workshop));
        equipment.setWorkshop("主板装配一车间");
        assertTrue(ProductionWorkshopCatalog.equipmentBelongsToWorkshop(equipment, workshop));
        equipment.setWorkshop("厂区主板装配一车间A");
        assertTrue(ProductionWorkshopCatalog.equipmentBelongsToWorkshop(equipment, workshop));
        equipment.setWorkshop("主板装配");
        assertTrue(ProductionWorkshopCatalog.equipmentBelongsToWorkshop(equipment, workshop));
        equipment.setWorkshop("电源板装配一车间");
        assertFalse(ProductionWorkshopCatalog.equipmentBelongsToWorkshop(equipment, workshop));
        assertFalse(ProductionWorkshopCatalog.equipmentBelongsToWorkshop(null, workshop));
        assertFalse(ProductionWorkshopCatalog.equipmentBelongsToWorkshop(equipment, null));
        equipment.setWorkshop(null);
        equipment.setEquipmentType(null);
        assertFalse(ProductionWorkshopCatalog.equipmentBelongsToWorkshop(equipment, workshop));
    }

    @Test
    void dispatchMembershipUsesEquipmentBeforeStepFallback() {
        var workshop = ProductionWorkshopCatalog.workshopByKey("mb-1");
        ProcessStep mainboard = step(1L, "主板装配");
        Equipment equipment = new Equipment();
        equipment.setWorkshop("主板装配一车间");
        assertFalse(ProductionWorkshopCatalog.dispatchBelongsToWorkshop(null, null, mainboard, equipment));
        assertTrue(ProductionWorkshopCatalog.dispatchBelongsToWorkshop(null, workshop, null, equipment));
        assertTrue(ProductionWorkshopCatalog.dispatchBelongsToWorkshop(null, workshop, mainboard, null));
        equipment.setWorkshop("电源板装配一车间");
        assertFalse(ProductionWorkshopCatalog.dispatchBelongsToWorkshop(null, workshop, mainboard, equipment));
        assertFalse(ProductionWorkshopCatalog.dispatchBelongsToWorkshop(null, workshop, null, null));
        assertFalse(ProductionWorkshopCatalog.dispatchBelongsToWorkshop(null, workshop, step(2L, "电源板装配"), null));
    }

    @Test
    void finishedGoodsUsesMinimumAcrossEightStagesAndSumsParallelWorkshops() {
        Map<Long, ProcessStep> steps = new HashMap<>();
        List<DispatchTask> tasks = new ArrayList<>();
        for (var stage : ProductionWorkshopCatalog.PRODUCTION_STAGES) {
            long id = stage.stepOrder();
            steps.put(id, step(id, stage.stepName()));
            tasks.add(dispatch(id, id == 3 ? "4" : "10"));
        }
        tasks.add(dispatch(3L, "3"));
        tasks.add(dispatch(99L, "500"));
        tasks.add(dispatch(8L, null));
        assertEquals(7, ProductionWorkshopCatalog.finishedGoodsQty(tasks, steps));
        assertEquals(10, ProductionWorkshopCatalog.finalStageCompletedQty(tasks, steps));
        tasks.removeIf(d -> d.getStepId() == 1L);
        assertEquals(0, ProductionWorkshopCatalog.finishedGoodsQty(tasks, steps));
        assertEquals(10, ProductionWorkshopCatalog.finalStageCompletedQty(tasks, steps));
    }

    @Test
    void missingDispatchesOrStepsProduceZeroQuantity() {
        assertEquals(0, ProductionWorkshopCatalog.finishedGoodsQty(null, null));
        assertEquals(0, ProductionWorkshopCatalog.finishedGoodsQty(List.of(), null));
        assertEquals(0, ProductionWorkshopCatalog.finishedGoodsQty(List.of(dispatch(1L, "2")), null));
        assertEquals(0, ProductionWorkshopCatalog.finalStageCompletedQty(null, null));
        assertEquals(0, ProductionWorkshopCatalog.finalStageCompletedQty(List.of(), null));
        assertEquals(0, ProductionWorkshopCatalog.finalStageCompletedQty(List.of(dispatch(1L, "2")), null));
    }

    private static ProcessStep step(Long id, String name) {
        ProcessStep step = new ProcessStep();
        step.setStepId(id);
        step.setStepName(name);
        step.setStepNo(id.intValue());
        step.setRouteId(1L);
        step.setStatus(1);
        return step;
    }

    private static DispatchTask dispatch(Long stepId, String completed) {
        DispatchTask task = new DispatchTask();
        task.setStepId(stepId);
        task.setCompletedQuantity(completed == null ? null : new BigDecimal(completed));
        return task;
    }
}
