package com.orange.controller;

import com.orange.service.JiraServicesss;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Path("/jira")
public class JirasController {
    @Inject
    JiraServicesss jiraServicesss;

    /**
     * Endpoint pour récupérer tous les projets Jira et leurs tickets associés.
     * @return Liste des projets avec leurs tickets.
     */
    @GET
    @Path("/projects-tickets")
    @Produces(MediaType.APPLICATION_JSON)
    public Map<String, List<Map<String, String>>> getAllProjectTickets() throws Exception {
        Map<String, List<Map<String, String>>> allProjectTickets = new HashMap<>();

        // Récupérer tous les projets
        List<Map<String, String>> projects = jiraServicesss.getAllProjects();

        // Pour chaque projet, récupérer ses tickets
        for (Map<String, String> project : projects) {
            String projectKey = project.get("key");
            List<Map<String, String>> tickets = jiraServicesss.getTicketsFromProject(projectKey);
            allProjectTickets.put(projectKey, tickets);
        }

        return allProjectTickets;
    }
}

