package com.upc.computer.service;

import com.upc.computer.entity.Equipment;
import com.upc.computer.mapper.AndonAlarmMapper;
import com.upc.computer.mapper.EquipmentMaintenanceRecordMapper;
import com.upc.computer.mapper.EquipmentMapper;
import com.upc.computer.mapper.UserMapper;
import com.upc.computer.service.impl.EquipmentServiceImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class EquipmentHealthBoundaryTest {
    @Mock private EquipmentMapper equipmentMapper;
    @Mock private AndonAlarmMapper alarmMapper;
    @Mock private EquipmentMaintenanceRecordMapper maintenanceMapper;
    @Mock private UserMapper userMapper;
    @InjectMocks private EquipmentServiceImpl service;

    @Test
    void minimumIntegerHashCannotProduceNegativeRunHours() {
        String equipmentCode = "polygenelubricants";
        assertEquals(Integer.MIN_VALUE, equipmentCode.hashCode());
        Equipment equipment = new Equipment();
        equipment.setEquipmentId(1L);
        equipment.setEquipmentCode(equipmentCode);
        when(equipmentMapper.equipmentList()).thenReturn(new ArrayList<>(List.of(equipment)));
        when(alarmMapper.listRecentAlarms(200)).thenReturn(new ArrayList<>());
        when(maintenanceMapper.maintenanceList()).thenReturn(new ArrayList<>());
        when(userMapper.userList()).thenReturn(new ArrayList<>());
        var result = service.calcHealthList();
        assertEquals(1, result.size());
        int runHours = ((Number) result.get(0).get("runHours")).intValue();
        assertTrue(runHours >= 100 && runHours <= 999, "runHours=" + runHours);
        verify(alarmMapper).listRecentAlarms(200);
        verify(equipmentMapper, never()).updateEquipment(any());
    }
}
