package com.yumi.catalog.settings;

import com.yumi.catalog.settings.SettingsViews.FormulaCatalogView;
import com.yumi.catalog.settings.SettingsViews.SettingsView;
import com.yumi.catalog.settings.SettingsViews.ValuesView;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 全局设置 API（design.md §6“全局设置”行）：单价与默认值、只读公式说明。
 * 星级/包装档位/缝边种类/员工工种的目录管理已迁至 {@link StaticDataController}（任务 2.22/2.25）。
 */
@RestController
@RequestMapping("/api/settings")
public class SettingsController {

    private final SettingsService settingsService;

    public SettingsController(SettingsService settingsService) {
        this.settingsService = settingsService;
    }

    @GetMapping
    public SettingsView getSettings() {
        return settingsService.view();
    }

    /** 只读公式说明（任务 2.20）：GET 不要求 Idempotency-Key，不产生业务写入，不可编辑。 */
    @GetMapping("/formulas")
    public FormulaCatalogView formulas() {
        return settingsService.formulaCatalog();
    }

    @PatchMapping
    public ValuesView patchValues(@RequestBody Map<String, String> patch) {
        return settingsService.patchValues(patch);
    }

}
