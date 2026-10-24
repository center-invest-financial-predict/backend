package com.example.demo.forecast;

import com.example.demo.entity.Direction;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CategoryClassifierTest {

    private static final String OWN = "0098524470";
    private final CategoryClassifier c = new CategoryClassifier();

    private String cat(Direction d, String cpInn, String name, String purpose) {
        return c.classify(OWN, d, cpInn, name, purpose);
    }

    @Test
    void classifiesDatasetStylePurposes() {
        assertThat(cat(Direction.EXPENSE, "7727406020", "УФК", "Единый налоговый платеж (ЕНП)")).isEqualTo("Налоги");
        assertThat(cat(Direction.EXPENSE, "0068275202", "ПАО «Банк»", "Перечисление заработной платы (аванс) за сентябрь"))
                .isEqualTo("Зарплата");
        assertThat(cat(Direction.EXPENSE, "0068275202", "ПАО «Банк»", "Погашение кредита по договору № КД-1404"))
                .isEqualTo("Кредит");
        assertThat(cat(Direction.EXPENSE, "0068275202", "ПАО «Банк»", "Комиссия за ведение счета за сентябрь"))
                .isEqualTo("Банк");
        assertThat(cat(Direction.EXPENSE, OWN, "ООО «Грузоперевозки»", "Перевод собственных средств на расчетный счет"))
                .isEqualTo("Переводы");
        assertThat(cat(Direction.EXPENSE, "0095358401", "ООО «Запчасти»", "Оплата по счету № 3768 за запчасти"))
                .isEqualTo("Поставщики");
        assertThat(cat(Direction.INCOME, "0053443398", "ООО «Клиент»", "Оплата по УПД № 296")).isEqualTo("Продажи");
    }

    @Test
    void vatRemarkIsNotATaxPayment() {
        assertThat(cat(Direction.INCOME, "0059743800", "ООО «Клиент»", "Оплата по УПД № 879. Без налога (НДС)"))
                .isEqualTo("Продажи");
        assertThat(cat(Direction.EXPENSE, "0059743800", "ООО «Поставщик»", "Оплата по счету № 1 за услуги. Без налога (НДС)"))
                .isEqualTo("Поставщики");
    }
}
