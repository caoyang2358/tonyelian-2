package cn.copper.phase2.reference;

import cn.copper.phase2.reference.ReferenceDataModels.ReferenceData;
import org.springframework.stereotype.Service;

/** BASE-04：三组来自一期基础字典，两组来自二期研发字典。 */
@Service
public class ReferenceDataService {
    private final ReferenceDataRepository repository;

    public ReferenceDataService(ReferenceDataRepository repository) {
        this.repository = repository;
    }

    public ReferenceData referenceData() {
        return new ReferenceData(
                repository.workshops(),
                repository.processes(),
                repository.units(),
                ReferenceDictionaries.EQUIPMENT_TYPES,
                ReferenceDictionaries.MANAGEMENT_STATUSES);
    }
}
