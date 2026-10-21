package com.yumi.product.api;

/**
 * 商品录入配置命令（交付清单 D02）。
 *
 * <p>需要 {@code product.update}；配置变化只影响后续录入，不刷新既有商品或历史快照。
 */
public interface ProductEntryConfigurationService {

    ProductEntryConfigurationView getConfiguration();

    void saveDefaults(SaveProductEntryDefaultsCommand command);

    void saveMakingStar(SaveMakingStarOptionCommand command);

    void saveProcessTimeOption(SaveProcessTimeOptionCommand command);
}
