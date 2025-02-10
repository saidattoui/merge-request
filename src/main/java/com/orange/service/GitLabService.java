package com.orange.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

/**
 * Service pour interagir avec les API GitLab et Jira.
 * <p>
 * Ce service a été optimisé pour améliorer le temps de réponse et l’utilisation des ressources grâce à :
 * - Un client HTTP configuré avec des timeouts.
 * - Un pool de threads réutilisable pour paralléliser les appels API.
 * - Une mise en cache en mémoire pour les appels sur des endpoints moins dynamiques (subgroups, projects et tickets Jira).
 * Chaque méthode est commentée pour faciliter la maintenance.
 * </p>
 */
@ApplicationScoped
public class GitLabService {

    private static final Logger LOG = Logger.getLogger(GitLabService.class);

    @ConfigProperty(name = "gitlab.api.token")
    private String TOKEN;
    @ConfigProperty(name = "gitlab.api.url")
    private String BASE_URL;
    @ConfigProperty(name = "jira.api.url")
    private String JIRA_BASE_URL;
    @ConfigProperty(name = "jira.api.token")
    private String AUTHORIZATION_HEADER;

    private final OkHttpClient client;
    private final ObjectMapper objectMapper;
    // Pool de threads réutilisable pour exécuter les appels en parallèle
    private final ExecutorService executor = Executors.newFixedThreadPool(10);

    // Cache en mémoire pour certains appels (20 minutes)
    private final Map<String, CacheEntry> cache = new ConcurrentHashMap<>();
    private final Map<String, CacheEntry> jiraCache = new ConcurrentHashMap<>();

    // Durée de vie du cache en secondes (ici 20 minutes)
    private static final long CACHE_TTL_SECONDS = 1200;

    // Classe interne pour stocker la réponse et son timestamp
    private static class CacheEntry {
        final JsonNode node;
        final long timestamp;
        CacheEntry(JsonNode node, long timestamp) {
            this.node = node;
            this.timestamp = timestamp;
        }
    }

    public GitLabService() {
        // Configuration du client HTTP avec des timeouts pour éviter les blocages
        this.client = new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .build();
        this.objectMapper = new ObjectMapper();
    }

    /**
     * Récupère toutes les données de merge requests depuis GitLab pour un groupe donné et applique la pagination.
     * Les appels aux sous-groupes et aux projets sont parallélisés et certains résultats sont mis en cache pour améliorer la réactivité.
     *
     * @param groupId  l’identifiant du groupe GitLab.
     * @param page     la page actuelle pour la pagination.
     * @param pageSize le nombre d’éléments par page.
     * @return une Map contenant les données formatées et les infos de pagination.
     * @throws IOException          en cas d’erreur d’appel API.
     * @throws ExecutionException   si une tâche parallèle échoue.
     * @throws InterruptedException si l’exécution est interrompue.
     */

    public Map<String, Object> getAllData(String groupId, int page, int pageSize)
            throws IOException, ExecutionException, InterruptedException {
        List<Future<List<Map<String, Object>>>> futures = new ArrayList<>();

        // Récupération (avec cache) des sous-groupes du groupe
        final JsonNode subgroups = getCachedResponse(BASE_URL + "/groups/" + groupId + "/subgroups");

        // Pour chaque sous-groupe, soumettre une tâche pour récupérer les projets et leurs merge requests
        for (final JsonNode subgroup : subgroups) {
            final String subgroupId = subgroup.get("id").asText();
            futures.add(executor.submit(() -> {
                List<Map<String, Object>> subgroupMergeRequests = new ArrayList<>();
                // Récupération (avec cache) des projets du sous-groupe
                final JsonNode projects = getCachedResponse(BASE_URL + "/groups/" + subgroupId + "/projects");
                for (final JsonNode project : projects) {
                    final String projectId = project.get("id").asText();
                    // Les merge requests sont plus dynamiques, pas de mise en cache ici
                    final JsonNode mergeRequests = executeRequest(BASE_URL + "/projects/" + projectId + "/merge_requests?state=opened");
                    for (final JsonNode mr : mergeRequests) {
                        Map<String, String> mrData = buildMrData(mr);
                        Map<String, Object> enrichedMr = new HashMap<>();
                        enrichedMr.put("subgroup", subgroup.get("name").asText());
                        enrichedMr.put("project", project.get("name").asText());
                        enrichedMr.putAll(mrData);
                        subgroupMergeRequests.add(enrichedMr);
                    }
                }
                return subgroupMergeRequests;
            }));
        }

        // Agrégation des résultats des tâches parallèles
        List<Map<String, Object>> allMergeRequests = new ArrayList<>();
        for (Future<List<Map<String, Object>>> future : futures) {
            allMergeRequests.addAll(future.get());
        }

        // Calcul de la pagination
        int totalItems = allMergeRequests.size();
        int totalPages = (int) Math.ceil((double) totalItems / pageSize);
        page = Math.max(1, Math.min(page, totalPages));
        int start = (page - 1) * pageSize;
        int end = Math.min(start + pageSize, totalItems);
        List<Map<String, Object>> pagedData = allMergeRequests.subList(start, end);

        // Regroupement des MR par sous-groupe et projet
        Map<String, Map<String, List<Map<String, Object>>>> result = new LinkedHashMap<>();
        for (Map<String, Object> mr : pagedData) {
            String subgroup = (String) mr.get("subgroup");
            String project = (String) mr.get("project");
            mr.remove("subgroup");
            mr.remove("project");
            result.computeIfAbsent(subgroup, k -> new LinkedHashMap<>())
                    .computeIfAbsent(project, k -> new ArrayList<>())
                    .add(mr);
        }

        // Formatage final du résultat
        List<Map<String, Object>> formattedResult = new ArrayList<>();
        result.forEach((subgroup, projects) -> {
            Map<String, Object> subgroupMap = new LinkedHashMap<>();
            subgroupMap.put("subgroup", subgroup);
            List<Map<String, Object>> projectList = new ArrayList<>();
            projects.forEach((project, mrs) -> {
                Map<String, Object> projectMap = new LinkedHashMap<>();
                projectMap.put("project", project);
                projectMap.put("mergeRequests", mrs);
                projectList.add(projectMap);
            });
            subgroupMap.put("projects", projectList);
            formattedResult.add(subgroupMap);
        });

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("data", formattedResult);
        response.put("pagination", Map.of(
                "currentPage", page,
                "pageSize", pageSize,
                "totalItems", totalItems,
                "totalPages", totalPages
        ));
        return response;
    }

    /**
     * Extrait et enrichit les données d'une merge request.
     * Si la branche source contient une référence à un ticket Jira, une tentative de récupération des détails du ticket est effectuée.
     *
     * @param mr le noeud JSON représentant la merge request.
     * @return une Map contenant les informations de la merge request.
     * @throws IOException en cas d’erreur lors de l’appel à l’API Jira.
     */
    private Map<String, String> buildMrData(JsonNode mr) throws IOException {
        Map<String, String> mrData = new HashMap<>();
        mrData.put("title", mr.has("title") ? mr.get("title").asText() : "N/A");
        mrData.put("author", (mr.has("author") && mr.get("author").has("name"))
                ? mr.get("author").get("name").asText() : "Unknown");

        if (mr.has("source_branch")) {
            String branchName = mr.get("source_branch").asText();
            mrData.put("sourceBranch", branchName);
            String ticketKey = extractTicketKeyFromBranch(branchName);
            if (ticketKey != null) {
                mrData.put("jiraTicket", ticketKey);
                try {
                    Map<String, String> jiraData = getCachedJiraTicketData(ticketKey);
                    mrData.putAll(jiraData);
                    LOG.info("Ticket Key: " + ticketKey + " | Status: " + jiraData.get("ticketStatus"));
                } catch (IOException e) {
                    mrData.put("jiraError", "Impossible de récupérer les données du ticket Jira");
                    LOG.error("Error retrieving Jira ticket data for " + ticketKey, e);
                }
            }
        }
        return mrData;
    }




    /**
     * Récupère (avec mise en cache) les sous-groupes d'un groupe GitLab.
     *
     * @param groupId l’identifiant du groupe.
     * @return un JsonNode contenant les sous-groupes.
     * @throws IOException en cas d’erreur d’appel API.
     */
    private JsonNode getSubgroups(String groupId) throws IOException {
        String url = BASE_URL + "/groups/" + groupId + "/subgroups";
        return getCachedResponse(url);
    }

    /**
     * Récupère (avec mise en cache) les projets d'un sous-groupe GitLab.
     *
     * @param subgroupId l’identifiant du sous-groupe.
     * @return un JsonNode contenant les projets.
     * @throws IOException en cas d’erreur d’appel API.
     */
    private JsonNode getProjects(String subgroupId) throws IOException {
        String url = BASE_URL + "/groups/" + subgroupId + "/projects";
        return getCachedResponse(url);
    }

    /**
     * Récupère les merge requests ouvertes d'un projet GitLab.
     *
     * @param projectId l’identifiant du projet.
     * @return un JsonNode contenant les merge requests.
     * @throws IOException en cas d’erreur d’appel API.
     */
    private JsonNode getMergeRequests(String projectId) throws IOException {
        String url = BASE_URL + "/projects/" + projectId + "/merge_requests?state=opened";
        return executeRequest(url);
    }

    /**
     * Exécute une requête HTTP GET vers l’URL spécifiée et retourne la réponse JSON.
     *
     * @param url l’URL de l’API à appeler.
     * @return un JsonNode contenant la réponse.
     * @throws IOException en cas d’erreur lors de l’appel API.
     */
    private JsonNode executeRequest(String url) throws IOException {
        Request request = new Request.Builder()
                .url(url)
                .header("Authorization", "Bearer " + TOKEN)
                .build();
        try (Response response = client.newCall(request).execute()) {
            if (response.isSuccessful() && response.body() != null) {
                return objectMapper.readTree(response.body().string());
            } else {
                throw new IOException("Erreur lors de l'appel API : " + response.code() + " - " + response.message());
            }
        }
    }

    /**
     * Vérifie si une réponse pour une URL donnée est dans le cache (avec TTL) et la retourne,
     * sinon exécute la requête et met en cache le résultat.
     *
     * @param url l’URL de l’API.
     * @return le JsonNode de réponse.
     * @throws IOException en cas d’erreur d’appel.
     */
    private JsonNode getCachedResponse(String url) throws IOException {
        long now = System.currentTimeMillis();
        CacheEntry entry = cache.get(url);
        if (entry != null && (now - entry.timestamp) < CACHE_TTL_SECONDS * 1000) {
            return entry.node;
        }
        JsonNode node = executeRequest(url);
        cache.put(url, new CacheEntry(node, now));
        return node;
    }

    /**
     * Récupère (avec mise en cache) les données d'un ticket Jira.
     *
     * @param ticketKey la clé du ticket Jira.
     * @return une Map contenant les informations du ticket.
     * @throws IOException en cas d’erreur lors de l’appel à l’API Jira.
     */
    public Map<String, String> getJiraTicketData(String ticketKey) throws IOException {
        // Appel direct sans cache (utilisé par getCachedJiraTicketData)
        String url = JIRA_BASE_URL + "/issue/" + ticketKey;
        Request request = new Request.Builder()
                .url(url)
                .header("Authorization", AUTHORIZATION_HEADER)
                .build();
        try (Response response = client.newCall(request).execute()) {
            if (response.isSuccessful() && response.body() != null) {
                JsonNode ticketData = objectMapper.readTree(response.body().string());
                Map<String, String> data = new HashMap<>();
                data.put("ticketKey", ticketData.get("key").asText());
                data.put("ticketStatus", ticketData.get("fields").get("status").get("name").asText());
                data.put("ticketAssignee", ticketData.get("fields").get("assignee") != null
                        ? ticketData.get("fields").get("assignee").get("displayName").asText()
                        : "Unassigned");
                data.put("ticketPriority", ticketData.get("fields").get("priority") != null
                        ? ticketData.get("fields").get("priority").get("name").asText()
                        : "Non spécifié");

                JsonNode timeTracking = ticketData.get("fields").get("timetracking");
                if (timeTracking != null && timeTracking.has("originalEstimate")) {
                    String originalEstimate = timeTracking.get("originalEstimate").asText();
                    String formattedEstimate = originalEstimate.replace("d", " jours").replace("h", " heures");
                    data.put("ticketTimeEstimate", formattedEstimate);
                } else {
                    data.put("ticketTimeEstimate", "Non spécifié");
                }
                return data;
            } else {
                throw new IOException("Erreur lors de l'appel à l'API Jira : " + response.code());
            }
        }
    }

    /**
     * Récupère les données d'un ticket Jira en utilisant un cache (TTL de 5 minutes).
     *
     * @param ticketKey la clé du ticket Jira.
     * @return une Map contenant les informations du ticket.
     * @throws IOException en cas d’erreur lors de l’appel à l’API Jira.
     */
    private Map<String, String> getCachedJiraTicketData(String ticketKey) throws IOException {
        long now = System.currentTimeMillis();
        CacheEntry entry = jiraCache.get(ticketKey);
        if (entry != null && (now - entry.timestamp) < CACHE_TTL_SECONDS * 1000) {
            // Le ticket est stocké sous forme de Map convertie en JsonNode (pour simplifier, on le reconvertit ici)
            return objectMapper.convertValue(entry.node, Map.class);
        }
        Map<String, String> jiraData = getJiraTicketData(ticketKey);
        // Convertir la Map en JsonNode pour la stocker dans le cache
        JsonNode node = objectMapper.valueToTree(jiraData);
        jiraCache.put(ticketKey, new CacheEntry(node, now));
        return jiraData;
    }

    /**
     * Récupère les membres d'un projet GitLab.
     *
     * @param projectId l’identifiant du projet.
     * @return un JsonNode contenant les membres.
     * @throws IOException en cas d’erreur d’appel API.
     */
    public JsonNode getProjectMembers(String projectId) throws IOException {
        String url = BASE_URL + "/projects/" + projectId + "/members";
        Request request = new Request.Builder()
                .url(url)
                .header("Authorization", "Bearer " + TOKEN)
                .build();
        try (Response response = client.newCall(request).execute()) {
            if (response.isSuccessful()) {
                return objectMapper.readTree(response.body().string());
            } else {
                throw new IOException("Erreur lors de l'appel à l'API GitLab : " + response.code());
            }
        }
    }

    /**
     * Calcule et renvoie diverses statistiques concernant les merge requests GitLab et les tickets Jira.
     * Parcourt les tickets Jira et merge requests pour compter les tickets non assignés, à haute priorité, en retard, etc.
     *
     * @return une Map contenant les statistiques.
     * @throws IOException en cas d’erreur d’appel API.
     */
    public Map<String, Object> getStats() throws IOException {
        String groupId = "atoui";
        final JsonNode subgroups = getSubgroups(groupId);

        int openMRs = 0, highPriorityMRs = 0, unassignedTickets = 0, overdueTickets = 0;
        int openMRsToday = 0, highPriorityMRsToday = 0, unassignedTicketsToday = 0, overdueTicketsToday = 0;

        // Traitement des tickets Jira
        final JsonNode jiraTickets = getAllJiraTickets();
        final DateTimeFormatter jiraDateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSZ");
        for (final JsonNode ticket : jiraTickets) {
            final JsonNode fields = ticket.get("fields");
            if (fields == null) continue;
            LocalDate createdDate = null;
            if (fields.has("created")) {
                try {
                    String createdStr = fields.get("created").asText();
                    ZonedDateTime zdt = ZonedDateTime.parse(createdStr, jiraDateFormatter);
                    createdDate = zdt.toLocalDate();
                } catch (Exception e) {
                    LOG.error("Error parsing Jira created date: " + fields.get("created"), e);
                }
            }
            boolean isCreatedToday = (createdDate != null) && createdDate.isEqual(LocalDate.now());
            String assignee = (fields.has("assignee") && !fields.get("assignee").isNull())
                    ? fields.get("assignee").get("displayName").asText() : "Unassigned";
            String priority = (fields.has("priority") && !fields.get("priority").isNull())
                    ? fields.get("priority").get("name").asText() : "Non spécifié";

            if ("Unassigned".equals(assignee)) {
                unassignedTickets++;
                if (isCreatedToday) unassignedTicketsToday++;
            }
            if ("High".equalsIgnoreCase(priority)) {
                highPriorityMRs++;
                if (isCreatedToday) highPriorityMRsToday++;
            }
            if (fields.has("duedate") && !fields.get("duedate").isNull()) {
                try {
                    String dueDateStr = fields.get("duedate").asText();
                    LocalDate dueDate = LocalDate.parse(dueDateStr, DateTimeFormatter.ISO_LOCAL_DATE);
                    if (dueDate.isBefore(LocalDate.now())) {
                        overdueTickets++;
                        if (isCreatedToday) overdueTicketsToday++;
                    }
                } catch (Exception e) {
                    LOG.error("Error parsing due date: " + fields.get("duedate"), e);
                }
            }
        }

        // Traitement des merge requests GitLab
        for (final JsonNode subgroup : subgroups) {
            final String subgroupId = subgroup.get("id").asText();
            final JsonNode projects = getProjects(subgroupId);
            for (final JsonNode project : projects) {
                final String projectId = project.get("id").asText();
                final JsonNode mergeRequests = getMergeRequests(projectId);
                for (final JsonNode mr : mergeRequests) {
                    openMRs++;
                    if (mr.has("created_at")) {
                        try {
                            String createdAtStr = mr.get("created_at").asText();
                            LocalDateTime createdAt = LocalDateTime.parse(createdAtStr, DateTimeFormatter.ISO_OFFSET_DATE_TIME);
                            if (createdAt.toLocalDate().equals(LocalDate.now())) {
                                openMRsToday++;
                            }
                        } catch (Exception e) {
                            LOG.error("Error parsing MR created_at: " + mr.get("created_at"), e);
                        }
                    }
                }
            }
        }

        Map<String, Object> stats = new HashMap<>();
        stats.put("openMRs", openMRs);
        stats.put("highPriorityMRs", highPriorityMRs);
        stats.put("unassignedTickets", unassignedTickets);
        stats.put("overdueTickets", overdueTickets);
        stats.put("openMRsToday", openMRsToday);
        stats.put("highPriorityMRsToday", highPriorityMRsToday);
        stats.put("unassignedTicketsToday", unassignedTicketsToday);
        stats.put("overdueTicketsToday", overdueTicketsToday);
        stats.put("openMRsNotToday", openMRs - openMRsToday);
        stats.put("highPriorityMRsNotToday", highPriorityMRs - highPriorityMRsToday);
        stats.put("unassignedTicketsNotToday", unassignedTickets - unassignedTicketsToday);
        stats.put("overdueTicketsNotToday", overdueTickets - overdueTicketsToday);
        return stats;
    }

    /**
     * Récupère tous les tickets Jira actifs (statut différent de "Done").
     *
     * @return un JsonNode contenant le tableau des tickets Jira.
     * @throws IOException en cas d’erreur d’appel API.
     */
    public JsonNode getAllJiraTickets() throws IOException {
        String url = JIRA_BASE_URL + "/search?jql=statusCategory!=Done";
        Request request = new Request.Builder()
                .url(url)
                .header("Authorization", AUTHORIZATION_HEADER)
                .build();
        try (Response response = client.newCall(request).execute()) {
            if (response.isSuccessful() && response.body() != null) {
                return objectMapper.readTree(response.body().string()).get("issues");
            } else {
                throw new IOException("Erreur lors de l'appel à l'API Jira : " + response.code());
            }
        }
    }

    /**
     * Récupère et construit la liste des événements à partir des merge requests GitLab.
     * Un événement est généré si un ticket Jira est bloqué ou si une MR a été créée aujourd'hui.
     *
     * @return une liste d’événements .
     * @throws IOException en cas d’erreur d’appel API.
     */
    public List<Map<String, Object>> getEvents() throws IOException {
        String groupId = "atoui";
        final JsonNode subgroups = getSubgroups(groupId);
        List<Map<String, Object>> events = new ArrayList<>();

        for (final JsonNode subgroup : subgroups) {
            final String subgroupId = subgroup.get("id").asText();
            final JsonNode projects = getProjects(subgroupId);
            for (final JsonNode project : projects) {
                final String projectId = project.get("id").asText();
                final JsonNode mergeRequests = getMergeRequests(projectId);
                for (final JsonNode mr : mergeRequests) {
                    Map<String, String> mrData = buildMrData(mr);
                    String ticketKey = mrData.get("jiraTicket");
                    String ticketStatus = mrData.get("ticketStatus");

                    // Génération d'un événement si le ticket Jira est bloqué
                    if (ticketKey != null && "Blocked".equalsIgnoreCase(ticketStatus)) {
                        String eventMessage = ticketKey + " is blocked ";
                        events.add(createEvent(eventMessage, mr.get("created_at").asText(), "lock"));
                    }
                    // Génération d'un événement pour une MR créée aujourd'hui
                    if (mr.has("created_at")) {
                        try {
                            String createdAtStr = mr.get("created_at").asText();
                            LocalDateTime createdAt = LocalDateTime.parse(createdAtStr, DateTimeFormatter.ISO_OFFSET_DATE_TIME);
                            if (createdAt.toLocalDate().equals(LocalDate.now())) {
                                events.add(createEvent("MR by " + mrData.get("author") + " was created ", createdAtStr, "merge"));
                            }
                        } catch (Exception e) {
                            LOG.error("Error parsing MR created_at: " + mr.get("created_at"), e);
                        }
                    }
                }
            }
        }

        // Tri des événements du plus récent au plus ancien
        events.sort((e1, e2) -> Long.compare((long) e1.get("sortKey"), (long) e2.get("sortKey")));
        // Suppression de la clé de tri interne avant renvoi
        events.forEach(event -> event.remove("sortKey"));
        return events;
    }

    /**
     * Crée un événement avec message, timestamp formaté, et une icône.
     * Une clé de tri est ajoutée pour ordonner les événements.
     *
     * @param message   le message de l’événement.
     * @param timestamp le timestamp au format ISO_OFFSET_DATE_TIME.
     * @param icon      l’icône de l’événement.
     * @return une Map représentant l’événement.
     */
    private Map<String, Object> createEvent(String message, String timestamp, String icon) {
        Map<String, Object> event = new HashMap<>();
        event.put("message", message);
        event.put("timestamp", formatTimestamp(timestamp));
        event.put("icon", icon);
        event.put("sortKey", formatTimestampForSorting(timestamp));
        return event;
    }

    /**
     * Calcule une clé de tri basée sur la différence en minutes entre le timestamp et le moment actuel.
     *
     * @param timestamp le timestamp au format ISO_OFFSET_DATE_TIME.
     * @return la différence en minutes (clé de tri).
     */
    public static long formatTimestampForSorting(String timestamp) {
        try {
            DateTimeFormatter formatter = DateTimeFormatter.ISO_OFFSET_DATE_TIME;
            OffsetDateTime createdAt = OffsetDateTime.parse(timestamp, formatter);
            OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
            Duration duration = Duration.between(createdAt, now);
            return duration.toMinutes();
        } catch (Exception e) {
            System.err.println("Erreur lors du formatage du timestamp: " + timestamp);
            return Long.MAX_VALUE;
        }
    }

    /**
     * Formate un timestamp pour l’afficher sous forme relative (ex. "2h ago").
     *
     * @param timestamp le timestamp au format ISO_OFFSET_DATE_TIME.
     * @return une chaîne représentant le temps écoulé.
     */
    public static String formatTimestamp(String timestamp) {
        try {
            DateTimeFormatter formatter = DateTimeFormatter.ISO_OFFSET_DATE_TIME;
            OffsetDateTime createdAt = OffsetDateTime.parse(timestamp, formatter);
            OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
            Duration duration = Duration.between(createdAt, now);
            long days = duration.toDays();
            long hours = duration.toHours() % 24;
            long minutes = duration.toMinutes() % 60;

            if (days >= 1) {
                return days + "j ago";
            } else if (hours >= 1) {
                return hours + "h ago";
            } else {
                return minutes + "m ago";
            }
        } catch (Exception e) {
            System.err.println("Erreur lors du formatage du timestamp: " + timestamp);
            return timestamp;
        }
    }

    /**
     * Extrait la clé d’un ticket Jira à partir du nom de branche.
     * Exemple : "ABC-123-feature" donnera "ABC-123".
     *
     * @param branchName le nom de la branche.
     * @return la clé Jira ou null si non trouvée.
     */
   private String extractTicketKeyFromBranch(String branchName) {
        if (branchName == null || branchName.isEmpty()) {
            return null;
        }

        branchName = branchName.replace("\\", "/");

        Pattern pattern = Pattern.compile("\\b[A-Z]+-\\d+\\b");
        Matcher matcher = pattern.matcher(branchName);

        return matcher.find() ? matcher.group() : null;
    }

    /**
     * Arrête proprement le pool de threads à l’arrêt de l’application.
     */
    @PreDestroy
    public void shutdownExecutor() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(30, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            LOG.error("Executor shutdown interrupted", e);
            executor.shutdownNow();
        }
    }

    public Map<String, Object> searchMergeRequests(String groupId, String query)
            throws IOException, ExecutionException, InterruptedException {

        List<Future<List<Map<String, Object>>>> futures = new ArrayList<>();
        JsonNode subgroups = getCachedResponse(BASE_URL + "/groups/" + groupId + "/subgroups");

        // Récupération parallèle des MRs comme dans la méthode originale
        for (JsonNode subgroup : subgroups) {
            String subgroupId = subgroup.get("id").asText();
            futures.add(executor.submit(() -> {
                List<Map<String, Object>> subgroupMrs = new ArrayList<>();
                JsonNode projects = getCachedResponse(BASE_URL + "/groups/" + subgroupId + "/projects");

                for (JsonNode project : projects) {
                    String projectId = project.get("id").asText();
                    JsonNode mergeRequests = executeRequest(BASE_URL + "/projects/" + projectId + "/merge_requests?state=opened");

                    for (JsonNode mr : mergeRequests) {
                        Map<String, Object> enrichedMr = new HashMap<>();
                        enrichedMr.put("subgroup", subgroup.get("name").asText());
                        enrichedMr.put("project", project.get("name").asText());
                        enrichedMr.putAll(buildMrData(mr));
                        subgroupMrs.add(enrichedMr);
                    }
                }
                return subgroupMrs;
            }));
        }

        // Agrégation des résultats
        List<Map<String, Object>> allMrs = new ArrayList<>();
        for (Future<List<Map<String, Object>>> future : futures) {
            allMrs.addAll(future.get());
        }

        // Filtrage basé sur la requête
        List<Map<String, Object>> filteredMrs = allMrs.stream()
                .filter(mr -> matchesQuery(mr, query))
                .collect(Collectors.toList());

        // Structuration des résultats
        Map<String, Map<String, List<Map<String, Object>>>> groupedResults = new LinkedHashMap<>();

        for (Map<String, Object> mr : filteredMrs) {
            String subgroup = (String) mr.get("subgroup");
            String project = (String) mr.get("project");

            mr.remove("subgroup");
            mr.remove("project");

            groupedResults
                    .computeIfAbsent(subgroup, k -> new LinkedHashMap<>())
                    .computeIfAbsent(project, k -> new ArrayList<>())
                    .add(mr);
        }

        // Formatage final
        List<Map<String, Object>> formattedResults = new ArrayList<>();
        groupedResults.forEach((subgroup, projects) -> {
            Map<String, Object> subgroupEntry = new LinkedHashMap<>();
            List<Map<String, Object>> projectEntries = new ArrayList<>();

            projects.forEach((projectName, mrs) -> {
                Map<String, Object> projectEntry = new LinkedHashMap<>();
                projectEntry.put("project", projectName);
                projectEntry.put("mergeRequests", mrs);
                projectEntries.add(projectEntry);
            });

            subgroupEntry.put("subgroup", subgroup);
            subgroupEntry.put("projects", projectEntries);
            formattedResults.add(subgroupEntry);
        });

        return Map.of("data", formattedResults);
    }

    private boolean matchesQuery(Map<String, Object> mr, String query) {
        if (query == null || query.isEmpty()) return true;

        String lowerQuery = query.toLowerCase();
        return mr.entrySet().stream()
                .anyMatch(entry -> {
                    Object value = entry.getValue();
                    return value != null &&
                            value.toString().toLowerCase().contains(lowerQuery);
                });
    }

}
