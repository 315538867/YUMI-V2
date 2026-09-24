package com.yumi.catalog.settings;

import com.yumi.catalog.settings.StaticDataService.CategoryView;
import com.yumi.catalog.settings.StaticDataService.ItemRequest;
import com.yumi.catalog.settings.StaticDataService.ItemView;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 静态数据 API（任务 2.22，design.md §6“静态数据”行）：按系统固定类别 code 寻址；
 * 类别不可增删改名；星级/包装档位/缝边种类条目可增改删，员工工种仅可改名与启停。
 */
@RestController
@RequestMapping("/api/settings/static-data")
public class StaticDataController {

    private final StaticDataService service;

    public StaticDataController(StaticDataService service) {
        this.service = service;
    }

    @GetMapping
    public List<CategoryView> categories() {
        return service.categories();
    }

    @GetMapping("/{code}")
    public List<ItemView> items(@PathVariable String code) {
        return service.items(code);
    }

    @PostMapping("/{code}/items")
    @ResponseStatus(HttpStatus.CREATED)
    public ItemView create(@PathVariable String code, @RequestBody ItemRequest request) {
        return service.create(code, request);
    }

    @PatchMapping("/{code}/items/{id}")
    public ItemView update(@PathVariable String code, @PathVariable long id,
                           @RequestBody ItemRequest request) {
        return service.update(code, id, request);
    }

    @DeleteMapping("/{code}/items/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String code, @PathVariable long id) {
        service.delete(code, id);
    }
}
