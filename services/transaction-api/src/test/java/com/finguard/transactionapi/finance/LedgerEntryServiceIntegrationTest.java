package com.finguard.transactionapi.finance;

import com.finguard.transactionapi.finance.domain.*;
import com.finguard.transactionapi.finance.dto.*;
import com.finguard.transactionapi.finance.exception.*;
import com.finguard.transactionapi.finance.service.LedgerEntryService;
import com.finguard.transactionapi.finance.repository.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.context.annotation.*;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.jpa.*;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import javax.sql.DataSource;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class LedgerEntryServiceIntegrationTest {
    static AnnotationConfigApplicationContext context;
    static JdbcTemplate jdbc;
    static LedgerEntryService service;
    static String schema;
    static UUID owner = UUID.randomUUID(), other = UUID.randomUUID();
    static UUID account = UUID.randomUUID(), foreignAccount = UUID.randomUUID(), inactiveAccount = UUID.randomUUID();
    static UUID expense = UUID.randomUUID(), income = UUID.randomUUID(), foreignCategory = UUID.randomUUID(), inactiveCategory = UUID.randomUUID();

    @Configuration
    @EnableTransactionManagement
    @EnableJpaRepositories(basePackageClasses = LedgerEntryRepository.class)
    @Import(LedgerEntryService.class)
    static class Config {
        @Bean LocalContainerEntityManagerFactoryBean entityManagerFactory(DataSource dataSource) {
            var factory = new LocalContainerEntityManagerFactoryBean();
            factory.setDataSource(dataSource);
            factory.setPackagesToScan("com.finguard.transactionapi.finance.domain");
            factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
            factory.setJpaPropertyMap(Map.of("hibernate.hbm2ddl.auto", "validate", "hibernate.default_schema", schema));
            return factory;
        }
        @Bean JpaTransactionManager transactionManager(jakarta.persistence.EntityManagerFactory factory) {
            return new JpaTransactionManager(factory);
        }
    }

    @BeforeAll static void setup() {
        var ds = new DriverManagerDataSource(System.getenv().getOrDefault("DB_URL", "jdbc:postgresql://localhost:5432/finguard"),
                System.getenv().getOrDefault("DB_USERNAME", "finguard"), System.getenv("DB_PASSWORD"));
        schema = "ledger_test_" + UUID.randomUUID().toString().replace("-", "");
        Flyway.configure().dataSource(ds).schemas(schema).defaultSchema(schema).locations("classpath:db/migration").load().migrate();
        jdbc = new JdbcTemplate(ds);
        context = new AnnotationConfigApplicationContext();
        context.registerBean(DataSource.class, () -> ds);
        context.register(Config.class);
        context.refresh();
        service = context.getBean(LedgerEntryService.class);
        sql("INSERT INTO app_user(id,auth_subject,display_name) VALUES (?, 'ledger-a','A'), (?, 'ledger-b','B')", owner, other);
        sql("INSERT INTO financial_account(id,user_id,name,account_type,active) VALUES (?,?,'active','CASH',true),(?,?,'foreign','CASH',true),(?,?,'inactive','CASH',false)", account,owner,foreignAccount,other,inactiveAccount,owner);
        sql("INSERT INTO category(id,user_id,name,entry_type,active) VALUES (?,?,'expense','EXPENSE',true),(?,?,'income','INCOME',true),(?,?,'foreign','EXPENSE',true),(?,?,'inactive','EXPENSE',false)", expense,owner,income,owner,foreignCategory,other,inactiveCategory,owner);
    }
    @AfterAll static void cleanup() {
        if (context != null) context.close();
        if (jdbc != null && schema != null) jdbc.execute("DROP SCHEMA " + schema + " CASCADE");
    }
    static void sql(String query, Object... args) {
        // Qualify fixture tables; pooled/application connections keep their own schema settings.
        for (String table : List.of("app_user", "financial_account", "category", "ledger_entry"))
            query = query.replace(table, schema + "." + table);
        jdbc.update(query, args);
    }
    static CreateFinanceEntryRequest request(UUID accountId, UUID category, EntryType type, String amount, String currency) {
        return new CreateFinanceEntryRequest(accountId,category,type,new BigDecimal(amount),currency,
                LocalDate.of(2026,10,1),Instant.parse("2026-10-02T01:02:03Z"),"Manual entry");
    }
    static CreateFinanceEntryRequest valid() { return request(account,expense,EntryType.EXPENSE,"12.34","CAD"); }
    @Test void validExpensePersistsAndReadsWithIndependentDates() {
        var result = service.createEntry(owner, valid());
        assertEquals(result,service.findById(owner,result.id()));
        assertEquals(new BigDecimal("12.34"),result.amount());
        assertEquals(valid().transactionDate(),result.transactionDate());
        assertEquals(valid().occurredAt(),result.occurredAt());
        assertEquals(owner,jdbc.queryForObject("SELECT user_id FROM " + schema + ".ledger_entry WHERE id=?",UUID.class,result.id()));
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM " + schema + ".ledger_entry WHERE id=? AND source_batch_id IS NULL AND source_row_number IS NULL AND deleted_at IS NULL",Integer.class,result.id()));
    }
    @Test void validIncomeAndNullCategory() {
        var result=service.createEntry(owner,request(account,income,EntryType.INCOME,"100.00","CAD"));
        assertEquals(EntryType.INCOME,service.findById(owner,result.id()).entryType());
        var uncategorized=new CreateFinanceEntryRequest(account,null,EntryType.EXPENSE,BigDecimal.ONE,"CAD",LocalDate.now(),null,"");
        var saved=service.findById(owner,service.createEntry(owner,uncategorized).id());
        assertNull(saved.categoryId());
        assertNull(saved.occurredAt());
    }
    @Test void rejectsInvalidAccountOwnerAndInactiveOrMissingAccount() {
        for (var id : List.of(foreignAccount,inactiveAccount,UUID.randomUUID()))
            assertThrows(InvalidFinanceEntryException.class,()->service.createEntry(owner,request(id,expense,EntryType.EXPENSE,"1","CAD")));
    }
    @Test void rejectsInvalidCategoryOwnerTypeInactiveAndMissing() {
        for (var id : List.of(foreignCategory,income,inactiveCategory,UUID.randomUUID()))
            assertThrows(InvalidFinanceEntryException.class,()->service.createEntry(owner,request(account,id,EntryType.EXPENSE,"1","CAD")));
    }
    @Test void rejectsZeroNegativeScaleAndOverflowWithoutPersistence() {
        int before=jdbc.queryForObject("SELECT count(*) FROM " + schema + ".ledger_entry",Integer.class);
        for (String value : List.of("0","-1","1.001","100000000000000000"))
            assertThrows(InvalidFinanceEntryException.class,()->service.createEntry(owner,request(account,expense,EntryType.EXPENSE,value,"CAD")));
        assertEquals(before,jdbc.queryForObject("SELECT count(*) FROM " + schema + ".ledger_entry",Integer.class));
    }
    @Test void rejectsInvalidAndMismatchedCurrency() {
        for (String currency : List.of("cad","CA","ZZZ","USD"))
            assertThrows(InvalidFinanceEntryException.class,()->service.createEntry(owner,request(account,expense,EntryType.EXPENSE,"1",currency)));
    }
    @Test void hidesOtherUsersMissingAndSoftDeletedEntries() {
        var result=service.createEntry(owner,valid());
        var unauthorized=assertThrows(FinanceEntryNotFoundException.class,()->service.findById(other,result.id()));
        var missing=assertThrows(FinanceEntryNotFoundException.class,()->service.findById(owner,UUID.randomUUID()));
        assertEquals(missing.getMessage(),unauthorized.getMessage());
        sql("UPDATE ledger_entry SET deleted_at=now() WHERE id=?",result.id());
        assertThrows(FinanceEntryNotFoundException.class,()->service.findById(owner,result.id()));
    }
}
