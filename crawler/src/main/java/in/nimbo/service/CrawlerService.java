package in.nimbo.service;

import com.github.benmanes.caffeine.cache.Cache;
import in.nimbo.dao.elastic.ElasticDAO;
import in.nimbo.dao.hbase.HBaseDAO;
import in.nimbo.dao.redis.RedisDAO;
import in.nimbo.entity.Anchor;
import in.nimbo.entity.Meta;
import in.nimbo.entity.Page;
import in.nimbo.exception.HBaseException;
import in.nimbo.exception.LanguageDetectException;
import in.nimbo.service.kafka.ConsumerService;
import in.nimbo.utility.LinkUtility;
import org.jsoup.nodes.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.MalformedURLException;
import java.net.URISyntaxException;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

public class CrawlerService {
    private Logger logger = LoggerFactory.getLogger(ConsumerService.class);
    private Cache<String, LocalDateTime> cache;
    private HBaseDAO hBaseDAO;
    private ElasticDAO elasticDAO;
    private ParserService parserService;
    private RedisDAO redisDAO;

    public CrawlerService(Cache<String, LocalDateTime> cache,
                          HBaseDAO hBaseDAO, ElasticDAO elasticDAO,
                          ParserService parserService,
                          RedisDAO redisDAO) {
        this.cache = cache;
        this.hBaseDAO = hBaseDAO;
        this.elasticDAO = elasticDAO;
        this.parserService = parserService;
        this.redisDAO = redisDAO;
    }

    public Set<String> crawl(String siteLink) {
        Set<String> links = new HashSet<>();
        try {
            String siteDomain = LinkUtility.getMainDomain(siteLink);
            if (cache.getIfPresent(siteDomain) == null) {
                if (!redisDAO.contains(siteLink)) {
                    Optional<Page> pageOptional = getPage(siteLink);
                    cache.put(siteDomain, LocalDateTime.now());
                    if (pageOptional.isPresent()) {
                        Page page = pageOptional.get();
                        page.getAnchors().forEach(link -> links.add(link.getHref()));
                        if (hBaseDAO.add(page)) {
                            elasticDAO.save(page);
                        } else {
                            logger.warn("Unable to add page with link {} to HBase", page.getLink());
                        }
                    }
                    redisDAO.add(siteLink);
                    logger.info("get {}", siteLink);
                }
            } else {
                links.add(siteLink);
            }
        } catch (URISyntaxException e) {
            logger.warn("Illegal URL format: " + siteLink, e);
        } catch (HBaseException e) {
            logger.error("Unable to establish HBase connection", e);
            links.clear();
            links.add(siteLink);
        } catch (Exception e) {
            logger.error(e.getMessage(), e);
        }
        return links;
    }

    /**
     * crawl a site and return it's content as a page
     *
     * @param link link of site
     * @return page if able to crawl page
     */
    public Optional<Page> getPage(String link) {
        try {
            Optional<Document> documentOptional = parserService.getDocument(link);
            if (!documentOptional.isPresent()) {
                return Optional.empty();
            }
            Document document = documentOptional.get();
            String pageContentWithoutTag = document.text().replace("\n", " ");
            String pageContentWithTag = document.html();
            if (pageContentWithoutTag.isEmpty()) {
                logger.warn("There is no content for site: {}", link);
            } else if (parserService.isEnglishLanguage(pageContentWithoutTag)) {
                Set<Anchor> anchors = parserService.getAnchors(document);
                List<Meta> metas = parserService.getMetas(document);
                String title = parserService.getTitle(document);
                Page page = new Page(link, title, pageContentWithTag, pageContentWithoutTag, anchors, metas, 1.0);
                return Optional.of(page);
            }
        } catch (MalformedURLException e) {
            logger.warn("Unable to reverse link: {}", link);
        } catch (LanguageDetectException e) {
            logger.warn("Cannot detect language of site: {}", link);
        } catch (Exception e) {
            logger.error(e.getMessage(), e);
        }
        return Optional.empty();
    }
}
