package searchengine.controllers;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import searchengine.config.SitesList;
import searchengine.dto.statistics.StatisticsResponse;
import searchengine.services.IndexingService;
import searchengine.services.SearchService;
import searchengine.services.StatisticsService;

import java.util.HashMap;
import java.util.Map;
@RestController
@RequestMapping("/api")
public class ApiController {

    private static final Logger log = LoggerFactory.getLogger(ApiController.class);

    private final StatisticsService statisticsService;
    private final IndexingService indexingService;
    private final SearchService searchService;
    private final SitesList sitesList;

    public ApiController(StatisticsService statisticsService,
                         IndexingService indexingService,
                         SearchService searchService,
                         SitesList sitesList) {
        this.statisticsService = statisticsService;
        this.indexingService = indexingService;
        this.searchService = searchService;
        this.sitesList = sitesList;
    }

    @GetMapping("/statistics")
    public ResponseEntity<StatisticsResponse> statistics() {
        log.info("Запрос на получение статистики");
        return ResponseEntity.ok(statisticsService.getStatistics());
    }

    @GetMapping("/startIndexing")
    public ResponseEntity<Map<String, Object>> startIndexing() {
        Map<String, Object> response = new HashMap<>();

        if (indexingService.isIndexing()) {
            log.info("Попытка запуска индексации, но процесс уже запущен");
            response.put("result", false);
            response.put("error", "Индексация уже запущена");
            return ResponseEntity.badRequest().body(response);
        }

        boolean isStarted = indexingService.startIndexing();
        if (isStarted) {
            log.info("Индексация успешно запущена");
            response.put("result", true);
        } else {
            log.error("Не удалось запустить индексацию");
            response.put("result", false);
            response.put("error", "Не удалось запустить индексацию");
        }

        return ResponseEntity.ok(response);
    }

    @GetMapping("/stopIndexing")
    public ResponseEntity<Map<String, Object>> stopIndexing() {
        Map<String, Object> response = new HashMap<>();
        if (!indexingService.isIndexing()) {
            log.info("Индексация не запущена");
            response.put("result", false);
            response.put("error", "Индексация не запущена");
            return ResponseEntity.badRequest().body(response);
        }

        boolean isStopped = indexingService.stopIndexing();
        if (isStopped) {
            log.info("Индексация успешно остановлена");
            response.put("result", true);
        } else {
            log.error("Не удалось остановить индексацию");
            response.put("result", false);
            response.put("error", "Не удалось остановить индексацию");
        }

        return ResponseEntity.ok(response);
    }

    @GetMapping("/search")
    public ResponseEntity<?> search(@RequestParam(value = "query", required = false) String query,
                                    @RequestParam(value = "site", required = false) String site,
                                    @RequestParam(value = "offset", defaultValue = "0") int offset,
                                    @RequestParam(value = "limit", defaultValue = "20") int limit) {
        log.info("Получен поисковый запрос: '{}', сайт: '{}', offset: {}, limit: {}", query, site, offset, limit);

        if (query == null || query.isBlank()) {
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("result", false);
            errorResponse.put("error", "Задан пустой поисковый запрос");
            return ResponseEntity.badRequest().body(errorResponse);
        }

        try {
            SearchService.SearchResponse searchResponse = searchService.search(query, site, offset, limit);
            return ResponseEntity.ok(searchResponse);
        } catch (IllegalArgumentException e) {
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("result", false);
            errorResponse.put("error", e.getMessage());
            return ResponseEntity.badRequest().body(errorResponse);
        } catch (Exception e) {
            log.error("Ошибка при выполнении поиска: {}", e.getMessage(), e);
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("result", false);
            errorResponse.put("error", "Внутренняя ошибка сервера");
            return ResponseEntity.status(500).body(errorResponse);
        }
    }

    @PostMapping("/indexPage")
    public ResponseEntity<Map<String, Object>> indexPage(@RequestParam("url") String url) {
        log.info("Запрос на индексацию страницы: {}", url);

        Map<String, Object> response = new HashMap<>();

        boolean urlValid = sitesList.getSites().stream()
                .anyMatch(site -> url.startsWith(site.getUrl()));

        if (!urlValid) {
            response.put("result", false);
            response.put("error", "Данная страница находится за пределами сайтов, указанных в конфигурационном файле");
            log.warn("Попытка индексировать страницу вне разрешённых сайтов: {}", url);
            return ResponseEntity.badRequest().body(response);
        }

        try {
            boolean success = indexingService.indexPage(url);
            if (success) {
                response.put("result", true);
                log.info("Страница успешно проиндексирована: {}", url);
                return ResponseEntity.ok(response);
            } else {
                response.put("result", false);
                response.put("error", "Не удалось проиндексировать страницу");
                log.error("Не удалось проиндексировать страницу: {}", url);
                return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
            }
        } catch (Exception e) {
            response.put("result", false);
            response.put("error", "Внутренняя ошибка сервера");
            log.error("Ошибка при индексации страницы {}: {}", url, e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
        }
    }
}