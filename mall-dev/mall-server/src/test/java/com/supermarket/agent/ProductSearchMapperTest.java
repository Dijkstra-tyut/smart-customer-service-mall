package com.supermarket.agent;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.handlers.CompositeEnumTypeHandler;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.supermarket.entity.Product;
import com.supermarket.enums.ProductStatusEnum;
import com.supermarket.mapper.ProductMapper;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

class ProductSearchMapperTest {
    @Test void searchReturnsSaleStatusAndExcludesOffShelfAndDeletedProducts() throws Exception {
        var source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:product_search_" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
        var jdbc = new JdbcTemplate(source);
        jdbc.execute("CREATE TABLE pms_product(id INT PRIMARY KEY,name VARCHAR(100),price DECIMAL(10,2),stock INT,category_id INT,detail VARCHAR(100),status INT,is_deleted INT,create_time TIMESTAMP)");
        jdbc.update("INSERT INTO pms_product(id,name,price,stock,status,is_deleted,create_time) VALUES (1,'milk on sale',58.90,100,1,0,CURRENT_TIMESTAMP),(2,'milk off shelf',20,10,0,0,CURRENT_TIMESTAMP),(3,'milk deleted',30,10,1,1,CURRENT_TIMESTAMP)");
        var configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.setDefaultEnumTypeHandler(CompositeEnumTypeHandler.class);
        var pagination = new MybatisPlusInterceptor();
        pagination.addInnerInterceptor(new PaginationInnerInterceptor(DbType.MYSQL));
        var factory = new MybatisSqlSessionFactoryBean();
        factory.setDataSource(source);
        factory.setConfiguration(configuration);
        factory.setPlugins(pagination);
        factory.setMapperLocations(new ClassPathResource("mapper/ProductMapper.xml"));
        try (var session = factory.getObject().openSession()) {
            var found = session.getMapper(ProductMapper.class).selectPageByKeyword(new Page<Product>(1, 10), "milk");
            assertThat(found.getTotal()).isEqualTo(1);
            assertThat(found.getRecords()).singleElement().satisfies(product -> {
                assertThat(product.getId()).isEqualTo(1);
                assertThat(product.getStatus()).isEqualTo(ProductStatusEnum.ON_SHELF);
                assertThat(product.getPrice()).isEqualByComparingTo("58.90");
                assertThat(product.getStock()).isEqualTo(100);
            });
        }
    }
}
