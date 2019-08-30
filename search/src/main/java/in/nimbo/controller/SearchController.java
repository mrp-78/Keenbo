package in.nimbo.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import emoji4j.EmojiUtils;
import in.nimbo.common.utility.FileUtility;
import in.nimbo.config.SparkConfig;
import in.nimbo.dao.redis.LabelDAO;
import in.nimbo.dao.elastic.ElasticDAO;
import in.nimbo.entity.*;

import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.util.*;
import java.util.stream.Collectors;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class SearchController {
    private static final String SEARCH_QUERY_REGEX = "^(.*?)(\\s+site:(.*))?$";
    private ElasticDAO elasticDAO;
    private SparkConfig config;
    private ObjectMapper mapper;
    private LabelDAO labelDAO;
    private GraphResponse wordGraph;
    private GraphResponse siteGraph;

    public SearchController(ElasticDAO elasticDAO, SparkConfig config, ObjectMapper mapper, LabelDAO labelDAO) {
        this.elasticDAO = elasticDAO;
        this.config = config;
        this.mapper = mapper;
        this.labelDAO = labelDAO;
    }

    public List<Page> search(String query) {
        Pattern pattern = Pattern.compile(SEARCH_QUERY_REGEX);
        Matcher matcher = pattern.matcher(query);
        if (matcher.find()) {
            String extractedQuery = matcher.group(1);
            String site = matcher.group(3);
            if (site == null) {
                site = "";
            }
            List<Page> pages = elasticDAO.search(extractedQuery, site);
            pages.forEach(page -> labelDAO.add(page.getLink(), page.getLabel()));
            return pages;
        }
        throw new AssertionError();
    }

    public GraphResponse siteGraph() throws IOException {
        if (siteGraph != null) {
            return siteGraph;
        }
        String wordGraphJson = FileUtility.readFileFromResource("site-graph");
        GraphResponse graphResponse = new Gson().fromJson(wordGraphJson, new TypeToken<GraphResponse>(){}.getType());
        List<Node> nodeList = graphResponse.getNodes();
        List<Node> filteredNodes = nodeList.stream().filter(node -> node.getFont().getSize() > config.getFilterNode()).collect(Collectors.toList());
        List<Edge> edges = graphResponse.getEdges();
        DoubleSummaryStatistics nodesSummary = filteredNodes.stream().mapToDouble(node -> node.getFont().getSize()).summaryStatistics();
        double maxNode = nodesSummary.getMax();
        double minNode = nodesSummary.getMin();
        filteredNodes.forEach(node -> node.getFont().setSize((node.getFont().getSize() - minNode) / (maxNode - minNode) * (config.getMaxNode() - config.getMinNode()) + config.getMinNode()));
        List<Edge> filteredEdges = edges.stream().filter(edge -> edge.getWidth() > config.getFilterEdge()).collect(Collectors.toList());
        filteredEdges = filteredEdges.stream().filter(edge -> filteredNodes.stream().anyMatch(dst -> dst.getId().equals(edge.getTo()) &&
                filteredNodes.stream().anyMatch(src -> src.getId().equals(edge.getFrom()) && (!dst.getId().equals(src.getId()))))).collect(Collectors.toList());
        IntSummaryStatistics edgesSummary = filteredEdges.stream().mapToInt(Edge::getWidth).summaryStatistics();
        int maxEdge = edgesSummary.getMax();
        int minEdge = edgesSummary.getMin();
        filteredEdges.forEach(edge -> scaleEdge(edge, minEdge, maxEdge));
        return siteGraph = new GraphResponse(filteredNodes, filteredEdges);
    }

    public GraphResponse wordGraph() {
        if (wordGraph != null) {
            return wordGraph;
        }
        String wordGraphJson = FileUtility.readFileFromResource("word-graph");
        GraphResponse graphResponse = new Gson().fromJson(wordGraphJson, new TypeToken<GraphResponse>(){}.getType());
        List<Node> nodeList = graphResponse.getNodes();
        List<Node> filteredNodes = nodeList.stream().filter(node -> !EmojiUtils.isEmoji(node.getId())).collect(Collectors.toList());
        List<Edge> edges = graphResponse.getEdges();
        filteredNodes.forEach(node -> node.getFont().setSize(config.getMinNode()));
        List<Edge> filteredEdges = edges.stream().filter(edge -> edge.getWidth() > config.getFilterEdge()).collect(Collectors.toList());
        List<Edge> filteredBadEdges = filteredEdges.stream().filter(edge -> filteredNodes.stream().anyMatch(dst -> dst.getId().equals(edge.getTo()) &&
                filteredNodes.stream().anyMatch(src -> src.getId().equals(edge.getFrom()) && (!dst.getId().equals(src.getId()))))).collect(Collectors.toList());
        List<Node> filteredBadNodes = filteredNodes.stream().filter(node -> filteredBadEdges.stream().anyMatch(edge -> edge.getFrom().equals(node.getId()) || edge.getTo().equals(node.getId()))).collect(Collectors.toList());
        IntSummaryStatistics edgesSummary = filteredEdges.stream().mapToInt(Edge::getWidth).summaryStatistics();
        int maxEdge = edgesSummary.getMax();
        int minEdge = edgesSummary.getMin();
        filteredEdges.forEach(edge -> scaleEdge(edge, minEdge, maxEdge));
        return wordGraph = new GraphResponse(filteredBadNodes, filteredBadEdges);
    }

    private void scaleEdge(Edge edge, int minEdge, int maxEdge) {
        int weight = ((int) (((double) edge.getWidth() - minEdge) * (config.getMaxEdge() - config.getMinEdge()) / (maxEdge - minEdge) + config.getMinEdge()));
        edge.setWidth(weight);
    }
}
