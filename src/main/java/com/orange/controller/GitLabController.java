package com.orange.controller;

import com.orange.service.GitLabService;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.enums.SchemaType;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;

/**
 * Contrôleur REST pour les endpoints liés à GitLab, jira.
 * Fournit des endpoints pour récupérer les données GitLab,jira les statistiques et les événements.
 */
@Path("/gitlab")
@Tag(name = "GitLab", description = "Endpoints pour  récupérer des informations sur les Merge Requests .")
public class GitLabController {

    private final GitLabService gitLabService;

    @Inject
    public GitLabController(GitLabService gitLabService) {
        this.gitLabService = gitLabService;
    }

    /**
     * Récupère les données paginées des merge requests GitLab.
     *
     * @param page la page actuelle (par défaut 1).
     * @param size le nombre d’éléments par page (par défaut 10).
     * @return une Map contenant les données et les informations de pagination.
     */
    @GET
    @Path("/data")

    @Produces(MediaType.APPLICATION_JSON)
    @Operation(
            summary = "Liste des Merge Requests GitLab",
            description = "Récupère une liste paginée des Merge Requests d'un groupe GitLab."
    )
    @APIResponse(
            responseCode = "200",
            description = "Liste des Merge Requests retournée avec succès",
            content = @Content(mediaType = MediaType.APPLICATION_JSON, schema = @Schema(type = SchemaType.OBJECT))
    )
    @APIResponse(responseCode = "500", description = "Erreur lors de la récupération des données")
    public Map<String, Object> getGitLabData(
            @Parameter(description = "Numéro de la page demandée", example = "1")
            @QueryParam("page") @DefaultValue("1") int page,

            @Parameter(description = "Nombre d’éléments par page", example = "10")
            @QueryParam("size") @DefaultValue("10") int size) {

        String groupId = "atoui";
        try {
            return gitLabService.getAllData(groupId, page, size);
        } catch (IOException | ExecutionException | InterruptedException e) {
            throw new RuntimeException("Erreur lors de la récupération des données GitLab", e);
        }
    }

    /**
     * Récupère les statistiques combinées GitLab et Jira.
     *
     * @return une Map contenant diverses statistiques.
     */
    @GET
    @Path("/stats")
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(
            summary = "Statistiques GitLab et Jira",
            description = "Récupère des statistiques agrégées depuis GitLab et Jira."
    )
    @APIResponse(
            responseCode = "200",
            description = "Statistiques récupérées avec succès",
            content = @Content(mediaType = MediaType.APPLICATION_JSON, schema = @Schema(type = SchemaType.OBJECT))
    )
    @APIResponse(responseCode = "500", description = "Erreur lors de la récupération des statistiques")
    public Map<String, Object> getGitLabStats() {
        try {
            return gitLabService.getStats();
        } catch (IOException e) {
            throw new RuntimeException("Erreur lors de la récupération des statistiques GitLab", e);
        }
    }

    /**
     * Récupère la liste des événements liés aux merge requests GitLab.
     *
     * @return une liste d’événements formatés.
     */
    @GET
    @Path("/events")
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(
            summary = "Liste des événements GitLab",
            description = "Retourne les événements liés aux Merge Requests GitLab."
    )
    @APIResponse(
            responseCode = "200",
            description = "Liste des événements récupérée avec succès",
            content = @Content(mediaType = MediaType.APPLICATION_JSON, schema = @Schema(type = SchemaType.ARRAY))
    )
    @APIResponse(responseCode = "500", description = "Erreur lors de la récupération des événements")
    public List<Map<String, Object>> getGitLabEvents() {
        try {
            return gitLabService.getEvents();
        } catch (IOException e) {
            throw new RuntimeException("Erreur lors de la récupération des événements GitLab", e);
        }
    }

    /**
     * Recherche les Merge Requests dans un groupe GitLab donné.
     *
     * @param query Texte de recherche pour filtrer les Merge Requests.
     * @return Une Map contenant les résultats de la recherche.
     */
    @GET
    @Path("/search")
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(
            summary = "Rechercher des Merge Requests",
            description = "Effectue une recherche parmi les Merge Requests d'un groupe GitLab."
    )
    @APIResponse(
            responseCode = "200",
            description = "Résultats de la recherche retournés avec succès",
            content = @Content(mediaType = MediaType.APPLICATION_JSON, schema = @Schema(type = SchemaType.OBJECT))
    )
    @APIResponse(responseCode = "500", description = "Erreur lors de la recherche des Merge Requests")
    public Map<String, Object> searchMergeRequests(
            @Parameter(description = "Texte de recherche pour filtrer les Merge Requests", required = true, example = "bug fix")
            @QueryParam("query") String query) {

        String groupId = "atoui";
        try {
            return gitLabService.searchMergeRequests(groupId, query);
        } catch (IOException | ExecutionException | InterruptedException e) {
            throw new RuntimeException("Erreur lors de la recherche des Merge Requests", e);
        }
    }
}
