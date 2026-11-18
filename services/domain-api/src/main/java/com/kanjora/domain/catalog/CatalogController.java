package com.kanjora.domain.catalog;

import com.kanjora.domain.catalog.CatalogDtos.CatalogSkillResponse;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/catalog")
public class CatalogController {

    private final CatalogRepository catalogRepository;

    public CatalogController(CatalogRepository catalogRepository) {
        this.catalogRepository = catalogRepository;
    }

    @GetMapping("/skills")
    public List<CatalogSkillResponse> getActiveSkills() {
        return catalogRepository.findActiveCatalog();
    }
}

