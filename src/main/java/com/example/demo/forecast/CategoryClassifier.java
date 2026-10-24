package com.example.demo.forecast;

import com.example.demo.entity.Direction;
import com.example.demo.entity.Operation;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;

/**
 * Rule based categorization of bank operations by payment purpose / counterparty.
 * Deterministic and explainable; the same category names are used by the UI design
 * (Продажи, Поставщики, Налоги, Аренда, Зарплата, Кредит, Прочее) plus two bank-specific ones.
 */
@Component
public class CategoryClassifier {

    /** Stateless shared instance for places that are not Spring beans (DTO mappers). */
    public static final CategoryClassifier DEFAULT = new CategoryClassifier();

    public static final String SALES = "Продажи";
    public static final String SUPPLIERS = "Поставщики";
    public static final String TAXES = "Налоги";
    public static final String RENT = "Аренда";
    public static final String SALARY = "Зарплата";
    public static final String CREDIT = "Кредит";
    public static final String BANK = "Банк";
    public static final String TRANSFER = "Переводы";
    public static final String OTHER = "Прочее";

    public static final List<String> ALL =
            List.of(SALES, SUPPLIERS, TAXES, RENT, SALARY, CREDIT, BANK, TRANSFER, OTHER);

    /** Categories treated as fixed costs by the "fixed_cost_growth" scenario. */
    public static final List<String> FIXED_COSTS = List.of(RENT, SALARY, CREDIT);

    public String classify(Operation op) {
        return classify(op.getCompanyInn(), op.getDirection(), op.getCounterpartyInn(),
                op.getCounterpartyName(), op.getPaymentPurpose());
    }

    public String classify(String companyInn, Direction direction, String counterpartyInn,
                           String counterpartyName, String purpose) {
        // "Без налога (НДС)" / "не облагается налогом" are VAT remarks, not tax payments.
        String text = (lower(purpose) + " " + lower(counterpartyName))
                .replace("без налога", " ")
                .replace("без налогов", " ")
                .replace("не облагается налогом", " ");

        if ((companyInn != null && companyInn.equals(counterpartyInn))
                || text.contains("собственных средств") || text.contains("между своими счетами")) {
            return TRANSFER;
        }
        if (hasAny(text, "заработн", "зарплат", "оплата труда", "выплата сотрудник", "отпускн")) {
            return SALARY;
        }
        if (hasAny(text, "налог", "енп", "ндфл", "усн", "страховые взнос", "взносы", "пфр", "фнс", "казначейств")) {
            return TAXES;
        }
        if (hasAny(text, "кредит", "займ", "лизинг", "погашение процентов")) {
            return CREDIT;
        }
        if (hasAny(text, "аренд", "коммунал", "жкх", "жку", "электроэнерг", "водоснабж", "отоплен")) {
            return RENT;
        }
        if (hasAny(text, "комиссия", "ведение счета", "обслуживание счета")) {
            return BANK;
        }
        if (direction == Direction.INCOME) {
            return SALES;
        }
        if (hasAny(text, "оплата", "счет", "счёт", "договор", "пополнение", "поставк", "закуп")) {
            return SUPPLIERS;
        }
        return OTHER;
    }

    /**
     * Distinguishes recurring series that share the same counterparty and category
     * (salary advance vs final settlement).
     */
    public String subKey(String category, String purpose) {
        if (SALARY.equals(category)) {
            return lower(purpose).contains("аванс") ? "advance" : "main";
        }
        return "";
    }

    private static boolean hasAny(String text, String... needles) {
        for (String n : needles) {
            if (text.contains(n)) {
                return true;
            }
        }
        return false;
    }

    private static String lower(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT);
    }
}
