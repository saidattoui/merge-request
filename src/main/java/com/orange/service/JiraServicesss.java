package com.orange.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@ApplicationScoped
public class JiraServicesss {
    private static final String AUTHORIZATION_HEADER = "Basic c2FpZC5hdG91aUBlc3ByaXQudG46QVRBVFQzeEZmR0YwbFIwVkVlOFN6UTVlNXdieDZ4clQzam15WmhEVmR3WlIxeU11WWxOVTBkQUJXMmtwdDc2MXlYZGZ0dGJqTDkza3RwUjYyVTR4T1k1Rlc1SktCX2o5VERVQ0k2UHRLT1ljY0Z1dkhpNFJ0VVRPUjkzOUhWSkRPMVd4dGdVYkFZTDVibWVsMGZlTHNYMHVZSnRweThnakhWLUROY0FSNUtwNzliZDdZLTBpU2xjPUI3RDI2Nzkz";

    private static final String BASE_URL = "https://esprit-team-jakiu0b4.atlassian.net/rest/api/2";
    private final OkHttpClient client;
    private final ObjectMapper objectMapper;


    public JiraServicesss() {
        this.client = new OkHttpClient();
        this.objectMapper = new ObjectMapper();

    }

    // Récupérer la liste de tous les projets
    public List<Map<String, String>> getAllProjects() throws IOException {
        String url = BASE_URL + "/project";  // L'endpoint pour récupérer tous les projets
        JsonNode projectsData = executeJiraRequest(url);

        List<Map<String, String>> projects = new ArrayList<>();
        for (JsonNode projectNode : projectsData) {
            Map<String, String> project = new HashMap<>();
            String projectName = projectNode.path("name").asText();
            String projectKey = projectNode.path("key").asText();

            project.put("name", projectName);
            project.put("key", projectKey);

            projects.add(project);
        }
        return projects;
    }


    public List<Map<String, String>> getTicketsFromProject(String projectKey) throws IOException {
        List<Map<String, String>> tickets = new ArrayList<>();

        String url = BASE_URL + "/search?jql=project=" + projectKey;
        JsonNode ticketsData = executeJiraRequest(url);

        for (JsonNode issueNode : ticketsData.path("issues")) {
            Map<String, String> ticket = new HashMap<>();
            String ticketId = issueNode.path("key").asText();
            String summary = issueNode.path("fields").path("summary").asText();
            String status = issueNode.path("fields").path("status").path("name").asText();
            String priority = issueNode.path("fields").path("priority").path("name").asText();

            ticket.put("ticketId", ticketId);
            ticket.put("summary", summary);
            ticket.put("status", status);
            ticket.put("priority", priority);

            tickets.add(ticket);
        }

        return tickets;
    }




    // Méthode pour exécuter la requête vers Jira
    private JsonNode executeJiraRequest(String url) throws IOException {
        Request request = new Request.Builder()
                .url(url)
                .header("Authorization", AUTHORIZATION_HEADER)
                .build();

        try (Response response = client.newCall(request).execute()) {
            if (response.isSuccessful() && response.body() != null) {
                String responseBody = response.body().string();
                return objectMapper.readTree(responseBody);
            } else {
                throw new IOException("Erreur lors de l'appel à Jira API : " + response.code());
            }
        }
    }
}