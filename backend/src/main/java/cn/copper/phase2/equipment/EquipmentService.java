package cn.copper.phase2.equipment;

import cn.copper.phase2.equipment.EquipmentModels.EquipmentList;
import org.springframework.stereotype.Service;

@Service
public class EquipmentService {
    private final EquipmentRepository repository;

    public EquipmentService(EquipmentRepository repository) {
        this.repository = repository;
    }

    public EquipmentList equipment() {
        return new EquipmentList(repository.productionEquipment());
    }
}
