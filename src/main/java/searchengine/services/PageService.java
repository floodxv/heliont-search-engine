package searchengine.services;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;


@Service
public class PageService {

    private final JdbcTemplate jdbcTemplate;

    @Autowired
    public PageService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @PostConstruct
    public void init() {
        String checkIndexSql = "SHOW INDEX FROM page WHERE Key_name = 'idx_path'";

        boolean indexExists = !jdbcTemplate.queryForList(checkIndexSql).isEmpty();

        if (!indexExists) {
            String createIndexSql = "CREATE INDEX idx_path ON page (path(191))";
            jdbcTemplate.execute(createIndexSql);
            System.out.println("Индекс 'idx_path' был успешно создан");
        } else {
            System.out.println("Индекс 'idx_path' уже существует");
        }
    }
}