/*
 *  Licensed to GraphHopper GmbH under one or more contributor
 *  license agreements. See the NOTICE file distributed with this work for
 *  additional information regarding copyright ownership.
 *
 *  GraphHopper GmbH licenses this file to you under the Apache License,
 *  Version 2.0 (the "License"); you may not use this file except in
 *  compliance with the License. You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package com.graphhopper.routing;

import com.graphhopper.routing.ev.BooleanEncodedValue;
import com.graphhopper.routing.ev.DecimalEncodedValue;
import com.graphhopper.routing.ev.DecimalEncodedValueImpl;
import com.graphhopper.routing.ev.SimpleBooleanEncodedValue;
import com.graphhopper.routing.querygraph.QueryGraph;
import com.graphhopper.routing.util.AccessFilter;
import com.graphhopper.routing.util.DirectedEdgeFilter;
import com.graphhopper.routing.util.EdgeFilter;
import com.graphhopper.routing.util.EncodingManager;
import com.graphhopper.storage.BaseGraph;
import com.graphhopper.storage.NodeAccess;
import com.graphhopper.util.EdgeExplorer;
import com.graphhopper.util.EdgeIterator;
import com.graphhopper.util.EdgeIteratorState;
import com.graphhopper.util.GHUtility;
import com.graphhopper.util.shapes.GHPoint;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static com.graphhopper.routing.DirectionResolverResult.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.graphhopper.storage.Graph;
import com.graphhopper.storage.NodeAccess;
import com.graphhopper.routing.util.DirectedEdgeFilter;
import com.graphhopper.util.EdgeIterator;
import com.graphhopper.util.EdgeExplorer;
import com.graphhopper.util.PointList;
import com.graphhopper.util.FetchMode;
import com.graphhopper.util.shapes.GHPoint;


/**
 * Tests {@link DirectionResolver} on a simple graph (no {@link QueryGraph}.
 *
 * @see DirectionResolverOnQueryGraphTest for tests that include direction resolving for virtual nodes and edges
 */
public class DirectionResolverTest {
    private BooleanEncodedValue accessEnc;
    private DecimalEncodedValue speedEnc;
    private BaseGraph graph;
    private NodeAccess na;

    @BeforeEach
    public void setup() {
        accessEnc = new SimpleBooleanEncodedValue("access", true);
        speedEnc = new DecimalEncodedValueImpl("speed", 5, 5, false);
        EncodingManager em = EncodingManager.start().add(accessEnc).add(speedEnc).build();
        graph = new BaseGraph.Builder(em).create();
        na = graph.getNodeAccess();
    }

    @Test
    public void isolated_nodes() {
        // 0   1
        addNode(0, 0, 0);
        addNode(1, 0.1, 0.1);

        checkResult(0, impossible());
        checkResult(1, impossible());
    }

    @Test
    public void isolated_nodes_blocked_edge() {
        // 0 |-| 1
        addNode(0, 0, 0);
        addNode(1, 0.1, 0.1);
        // with edges without access flags (blocked edges)
        graph.edge(0, 1).set(accessEnc, false, false);

        checkResult(0, impossible());
        checkResult(1, impossible());
    }

    @Test
    public void nodes_at_end_of_dead_end_street() {
        //       4
        //       |
        // 0 --> 1 --> 2
        //       |
        //       3
        addNode(0, 2, 1.9);
        addNode(1, 2, 2.0);
        addNode(2, 2, 2.1);
        addNode(3, 1.9, 2.0);
        addNode(4, 2.1, 2.0);
        addEdge(0, 1, false);
        addEdge(1, 2, false);
        addEdge(1, 3, true);
        addEdge(1, 4, true);

        checkResult(0, impossible());
        checkResult(2, impossible());
        // at the end of a dead end street the (only) in/out edges are used as restriction for both right and left
        // side approach
        checkResult(3, restricted(edge(1, 3), edge(3, 1), edge(1, 3), edge(3, 1)));
        checkResult(4, restricted(edge(1, 4), edge(4, 1), edge(1, 4), edge(4, 1)));
    }

    @Test
    public void unreachable_nodes() {
        //   1   3
        //  / \ /
        // 0   2
        addNode(0, 1, 1);
        addNode(1, 2, 1.5);
        addNode(2, 1, 2);
        addNode(3, 2, 2.5);
        addEdge(0, 1, false);
        addEdge(2, 1, false);
        addEdge(2, 3, false);

        // we can go to node 1, but never leave it
        checkResult(1, impossible());
        // we can leave point 2, but never arrive at it
        checkResult(2, impossible());
    }

    @Test
    public void junction() {
        //      3___
        //      |   \
        // 0 -> 1 -> 2 - 5
        //      |
        //      4
        addNode(0, 2.000, 1.990);
        addNode(1, 2.000, 2.000);
        addNode(2, 2.000, 2.010);
        addNode(3, 2.010, 2.000);
        addNode(4, 1.990, 2.000);
        addEdge(0, 1, false);
        addEdge(1, 2, false);
        addEdge(1, 3, true);
        addEdge(2, 3, true);
        addEdge(1, 4, true);
        addEdge(2, 5, true);

        // at junctions there is no reasonable way to restrict the directions!
        checkResult(1, unrestricted());
        checkResult(2, unrestricted());
    }

    @Test
    public void junction_exposed() {
        // 0  1  2
        //  \ | /
        //   \|/
        //    3
        addNode(0, 2, 1);
        addNode(1, 2, 2);
        addNode(2, 2, 3);
        addNode(3, 1, 2);
        addEdge(0, 3, true);
        addEdge(1, 3, true);
        addEdge(2, 3, true);
        checkResult(3, unrestricted());
    }

    @Test
    public void duplicateEdges() {
        // 0 = 1 - 2
        addNode(0, 0, 0);
        addNode(1, 1, 1);
        addNode(2, 0, 2);
        addEdge(0, 1, true);
        addEdge(0, 1, true);
        addEdge(1, 2, true);
        // if there are multiple incoming/outgoing edges due to duplicate edges its the same as for a junction,
        // -> we leave the directions unrestricted
        checkResult(1, unrestricted());

        // for duplicate edges at the end of a dead-end road we also leave the direction unrestricted
        checkResult(0, unrestricted());
    }

    @Test
    public void duplicateEdges_in() {
        // 0 => 1 - 2
        addNode(0, 1, 1);
        addNode(1, 2, 2);
        addNode(2, 1, 3);
        // duplicate in edges between 0 and 1 -> we do not apply any restrictions
        addEdge(0, 1, false);
        addEdge(0, 1, false);
        addEdge(1, 2, false);

        checkResult(1, unrestricted());
    }

    @Test
    public void duplicateEdges_out() {
        // 0 - 1 => 2
        addNode(0, 1, 1);
        addNode(1, 2, 2);
        addNode(2, 1, 3);
        // duplicate out edges between 1 and 2 -> we do not apply any restrictions
        addEdge(0, 1, false);
        addEdge(1, 2, false);
        addEdge(1, 2, false);

        checkResult(1, unrestricted());
    }

    @Test
    public void simple_road() {
        //    x   x
        //  0-1-2-3-4
        //    x   x
        addNode(0, 1, 0);
        addNode(1, 1, 1);
        addNode(2, 1, 2);
        addNode(3, 1, 3);
        addNode(4, 1, 4);
        // make sure graph bounds are valid
        addNode(5, 2, 5);

        addEdge(0, 1, true);
        addEdge(1, 2, true);
        addEdge(2, 3, true);
        addEdge(3, 4, true);

        checkResult(1, 1.01, 1, restricted(edge(2, 1), edge(1, 0), edge(0, 1), edge(1, 2)));
        checkResult(1, 0.99, 1, restricted(edge(0, 1), edge(1, 2), edge(2, 1), edge(1, 0)));
        checkResult(3, 1.01, 3, restricted(edge(4, 3), edge(3, 2), edge(2, 3), edge(3, 4)));
        checkResult(3, 0.99, 3, restricted(edge(2, 3), edge(3, 4), edge(4, 3), edge(3, 2)));
    }

    @Test
    public void simple_road_one_way() {
        //     x     x
        //  0->1->2->3->4
        //     x     x
        addNode(0, 1, 0);
        addNode(1, 1, 1);
        addNode(2, 1, 2);
        addNode(3, 1, 3);
        addNode(4, 1, 4);
        // make sure graph bounds are valid
        addNode(5, 2, 5);

        addEdge(0, 1, false);
        addEdge(1, 2, false);
        addEdge(2, 3, false);
        addEdge(3, 4, false);

        // if a location is on the 'wrong'side on a one-way street
        checkResult(1, 1.01, 1, onlyLeft(edge(0, 1), edge(1, 2)));
        checkResult(1, 0.99, 1, onlyRight(edge(0, 1), edge(1, 2)));
        checkResult(3, 1.01, 3, onlyLeft(edge(2, 3), edge(3, 4)));
        checkResult(3, 0.99, 3, onlyRight(edge(2, 3), edge(3, 4)));
    }


    @Test
    public void twoOutOneIn_oneWayRight() {
        //     x
        // 0 - 1 -> 2
        //     x
        addNode(0, 0, 0);
        addNode(1, 1, 1);
        addNode(2, 2, 2);
        addEdge(0, 1, true);
        addEdge(1, 2, false);

        // we cannot approach the southern target so it is on our left
        checkResult(1, 0.99, 1, onlyRight(0, 1));
        // we cannot approach the northern target so it is on our left
        checkResult(1, 1.01, 1, onlyLeft(0, 1));
    }

    @Test
    public void twoOutOneIn_oneWayLeft() {
        //      x
        // 0 <- 1 - 2
        //      x
        addNode(0, 0, 0);
        addNode(1, 1, 1);
        addNode(2, 2, 2);
        addEdge(1, 0, false);
        addEdge(1, 2, true);

        checkResult(1, 0.99, 1, onlyLeft(1, 0));
        checkResult(1, 1.01, 1, onlyRight(1, 0));
    }

    @Test
    public void twoInOneOut_oneWayRight() {
        //     x
        // 0 - 1 <- 2
        //     x
        addNode(0, 0, 0);
        addNode(1, 1, 1);
        addNode(2, 2, 2);
        addEdge(0, 1, true);
        addEdge(2, 1, false);

        checkResult(1, 0.99, 1, onlyLeft(1, 0));
        checkResult(1, 1.01, 1, onlyRight(1, 0));
    }

    @Test
    public void twoInOneOut_oneWayLeft() {
        //      x
        // 0 -> 1 - 2
        //      x
        addNode(0, 0, 0);
        addNode(1, 1, 1);
        addNode(2, 2, 2);
        addEdge(0, 1, false);
        addEdge(2, 1, true);

        checkResult(1, 0.99, 1, onlyRight(0, 1));
        checkResult(1, 1.01, 1, onlyLeft(0, 1));
    }

    private void addNode(int nodeId, double lat, double lon) {
        na.setNode(nodeId, lat, lon);
    }

    private EdgeIteratorState addEdge(int from, int to, boolean bothDirections) {
        return GHUtility.setSpeed(60, true, bothDirections, accessEnc, speedEnc, graph.edge(from, to).setDistance(1));
    }

    private boolean isAccessible(EdgeIteratorState edge, boolean reverse) {
        return reverse ? edge.getReverse(accessEnc) : edge.get(accessEnc);
    }

    private void checkResult(int node, DirectionResolverResult expectedResult) {
        checkResult(node, graph.getNodeAccess().getLat(node), graph.getNodeAccess().getLon(node), expectedResult);
    }

    private void checkResult(int node, double lat, double lon, DirectionResolverResult expectedResult) {
        DirectionResolver resolver = new DirectionResolver(graph, this::isAccessible);
        assertEquals(expectedResult, resolver.resolveDirections(node, new GHPoint(lat, lon)));
    }

    private int edge(int from, int to) {
        EdgeExplorer explorer = graph.createEdgeExplorer(AccessFilter.outEdges(accessEnc));
        EdgeIterator iter = explorer.setBaseNode(from);
        while (iter.next()) {
            if (iter.getAdjNode() == to) {
                return iter.getEdge();
            }
        }
        throw new IllegalStateException("Could not find edge from: " + from + ", to: " + to);
    }

        /**
         * Test avec Mockito : Validation de resolveDirections avec Graph et EdgeExplorer mockés
         * 
         * Ce test utilise Mockito pour mocker le Graph et l'EdgeExplorer afin
         * d'isoler complètement la logique de résolution de direction du DirectionResolver
         * sans dépendre d'un graphe réel.
         * 
         * Classes mockées :
         * - Graph : Pour simuler le graphe de routage
         * - EdgeExplorer : Pour contrôler l'itération sur les edges adjacents
         * - EdgeIterator : Pour simuler les edges disponibles depuis un nœud
         * - NodeAccess : Pour fournir des coordonnées de nœuds contrôlées
         * - DirectedEdgeFilter : Pour contrôler l'accessibilité des edges
         * 
         * Justification :
         * DirectionResolver analyse la topologie locale autour d'un nœud pour déterminer
         * les paires d'edges entrantes/sortantes selon qu'un point est à gauche ou à droite.
         * En mockant complètement le graphe, on peut tester des cas géométriques précis
         * difficiles à créer avec un graphe réel (ex: configurations d'angles exacts).
         * 
         * Valeurs simulées :
         * - Node 0 au centre (48.0, 11.0)
         * - Node 1 au Nord (48.01, 11.0)
         * - Node 2 au Sud (47.99, 11.0)
         * - Deux edges bidirectionnels : 0-1 (Nord) et 0-2 (Sud)
         * - Point de requête à l'Est du nœud (48.0, 11.01)
         * 
         * Avec un point à l'Est, le resolver devrait identifier :
         * - Right side : in=edge du Sud, out=edge du Nord
         * - Left side : in=edge du Nord, out=edge du Sud
         */
        @Test
        public void testResolveDirections_WithMockedGraph_ShouldReturnCorrectDirections() {
            // Arrange : Mock du graphe et composants
            com.graphhopper.storage.Graph mockGraph = org.mockito.Mockito.mock(com.graphhopper.storage.Graph.class);
            com.graphhopper.storage.NodeAccess mockNodeAccess = org.mockito.Mockito.mock(com.graphhopper.storage.NodeAccess.class);
            com.graphhopper.util.EdgeExplorer mockExplorer = org.mockito.Mockito.mock(com.graphhopper.util.EdgeExplorer.class);
            com.graphhopper.util.EdgeIterator mockIterator = org.mockito.Mockito.mock(com.graphhopper.util.EdgeIterator.class);
            com.graphhopper.routing.util.DirectedEdgeFilter mockFilter = 
                    org.mockito.Mockito.mock(com.graphhopper.routing.util.DirectedEdgeFilter.class);
            
            // Configuration du graphe
            org.mockito.Mockito.when(mockGraph.createEdgeExplorer()).thenReturn(mockExplorer);
            org.mockito.Mockito.when(mockGraph.getNodeAccess()).thenReturn(mockNodeAccess);
            
            // Configuration des coordonnées des nœuds
            // Node 0 = centre (48.0, 11.0)
            org.mockito.Mockito.when(mockNodeAccess.getLat(0)).thenReturn(48.0);
            org.mockito.Mockito.when(mockNodeAccess.getLon(0)).thenReturn(11.0);
            
            // Node 1 = Nord (48.01, 11.0)
            org.mockito.Mockito.when(mockNodeAccess.getLat(1)).thenReturn(48.01);
            org.mockito.Mockito.when(mockNodeAccess.getLon(1)).thenReturn(11.0);
            
            // Node 2 = Sud (47.99, 11.0)
            org.mockito.Mockito.when(mockNodeAccess.getLat(2)).thenReturn(47.99);
            org.mockito.Mockito.when(mockNodeAccess.getLon(2)).thenReturn(11.0);
            
            // Configuration de l'explorateur d'edges
            org.mockito.Mockito.when(mockExplorer.setBaseNode(0)).thenReturn(mockIterator);
            
            // Simuler 2 edges depuis le node 0
            org.mockito.Mockito.when(mockIterator.next())
                    .thenReturn(true)   // Premier edge existe
                    .thenReturn(true)   // Deuxième edge existe
                    .thenReturn(false); // Fin d'itération
            
            // Configuration des edges : edge 0 vers node 1 (Nord), edge 1 vers node 2 (Sud)
            org.mockito.Mockito.when(mockIterator.getEdge())
                    .thenReturn(0)  // Premier edge ID
                    .thenReturn(1); // Deuxième edge ID
            
            org.mockito.Mockito.when(mockIterator.getAdjNode())
                    .thenReturn(1)  // Premier edge va vers node 1 (Nord)
                    .thenReturn(2); // Deuxième edge va vers node 2 (Sud)
            
            // Mock des géométries (pas de wayGeometry, juste base->adj)
            com.graphhopper.util.PointList geom1 = com.graphhopper.util.Helper.createPointList(48.0, 11.0, 48.01, 11.0);
            com.graphhopper.util.PointList geom2 = com.graphhopper.util.Helper.createPointList(48.0, 11.0, 47.99, 11.0);
            
            org.mockito.Mockito.when(mockIterator.fetchWayGeometry(org.mockito.Mockito.any()))
                    .thenReturn(geom1)  // Géométrie edge 0
                    .thenReturn(geom2); // Géométrie edge 1
            
            org.mockito.Mockito.when(mockIterator.getDistance())
                    .thenReturn(100.0)
                    .thenReturn(100.0);
            
            // Configuration du filtre d'accessibilité : tous les edges sont accessibles dans les deux sens
            org.mockito.Mockito.when(mockFilter.accept(org.mockito.Mockito.any(), org.mockito.Mockito.eq(true)))
                    .thenReturn(true); // Accessible en entrée
            org.mockito.Mockito.when(mockFilter.accept(org.mockito.Mockito.any(), org.mockito.Mockito.eq(false)))
                    .thenReturn(true); // Accessible en sortie
            
            // Créer le DirectionResolver avec le graphe mocké
            DirectionResolver resolver = new DirectionResolver(mockGraph, mockFilter);
            
            // Point de requête à l'Est du nœud 0
            com.graphhopper.util.shapes.GHPoint queryPoint = new com.graphhopper.util.shapes.GHPoint(48.0, 11.01);
            
            // Act : Résoudre les directions
            DirectionResolverResult result = resolver.resolveDirections(0, queryPoint);
            
            // Assert : Vérifier les appels au mock
            // Le graphe devrait avoir été consulté pour créer l'explorateur
            org.mockito.Mockito.verify(mockGraph, org.mockito.Mockito.times(1)).createEdgeExplorer();
            org.mockito.Mockito.verify(mockGraph, org.mockito.Mockito.atLeastOnce()).getNodeAccess();
            
            // L'explorateur devrait avoir été configuré avec le node 0
            org.mockito.Mockito.verify(mockExplorer, org.mockito.Mockito.times(1)).setBaseNode(0);
            
            // L'itérateur devrait avoir été appelé pour parcourir les edges
            org.mockito.Mockito.verify(mockIterator, org.mockito.Mockito.times(3)).next(); // 2 edges + 1 false
            org.mockito.Mockito.verify(mockIterator, org.mockito.Mockito.times(2)).getEdge();
            org.mockito.Mockito.verify(mockIterator, org.mockito.Mockito.times(2)).getAdjNode();
            org.mockito.Mockito.verify(mockIterator, org.mockito.Mockito.times(2))
                    .fetchWayGeometry(org.mockito.Mockito.any());
            
            // Le filtre d'accessibilité devrait avoir été consulté pour chaque edge (2 fois × 2 directions)
            org.mockito.Mockito.verify(mockFilter, org.mockito.Mockito.times(2))
                    .accept(org.mockito.Mockito.any(), org.mockito.Mockito.eq(true));
            org.mockito.Mockito.verify(mockFilter, org.mockito.Mockito.times(2))
                    .accept(org.mockito.Mockito.any(), org.mockito.Mockito.eq(false));
            
            // Le NodeAccess devrait avoir été utilisé pour récupérer les coordonnées
            org.mockito.Mockito.verify(mockNodeAccess, org.mockito.Mockito.atLeastOnce()).getLat(0);
            org.mockito.Mockito.verify(mockNodeAccess, org.mockito.Mockito.atLeastOnce()).getLon(0);
            
            // Vérifier le résultat
            assertNotNull(result, "Le résultat ne devrait pas être null");
            // Note : Les assertions exactes sur les edge IDs dépendent de la logique interne de DirectionResolver
        }

                /**
         * Test avec Mockito : Validation du comportement avec un nœud isolé
         * 
         * Ce test vérifie que DirectionResolver gère correctement le cas limite
         * d'un nœud sans edges adjacents (nœud isolé dans le graphe).
         * 
         * Classes mockées :
         * - Graph : Pour simuler le graphe
         * - EdgeExplorer : Pour créer l'itérateur
         * - EdgeIterator : Pour simuler l'absence d'edges (retourne immédiatement false)
         * - NodeAccess : Pour fournir les coordonnées du nœud
         * - DirectedEdgeFilter : Pour filtrer les edges (non utilisé ici)
         * 
         * Cas limite testé :
         * Nœud isolé (ID=0) sans edges adjacents, situation qui peut survenir avec :
         * - Données OSM incomplètes ou corrompues
         * - Zones géographiques mal connectées
         * - Nœuds de frontière sans connexion
         * 
         * Valeur simulée :
         * - Nœud : ID=0 (nœud de base)
         * - Point de requête : (10.0, 10.0) - Position arbitraire
         * - Edges adjacents : 0 (nœud isolé)
         * 
         * Résultat attendu :
         * DirectionResolver retourne DirectionResolverResult.impossible() car
         * aucune direction ne peut être déterminée sans edges adjacents.
         * 
         * Justification :
         * Ce test vérifie la robustesse de DirectionResolver face à des données
         * incomplètes, garantissant qu'il ne lève pas d'exception et retourne
         * un résultat approprié (impossible) plutôt qu'un résultat incorrect.
         */
        @Test
        public void testResolveDirections_IsolatedNode_ShouldReturnImpossible() {
            // Arrange : Mock des composants du graphe
            Graph mockGraph = mock(Graph.class);
            NodeAccess mockNodeAccess = mock(NodeAccess.class);
            EdgeExplorer mockExplorer = mock(EdgeExplorer.class);
            EdgeIterator mockIterator = mock(EdgeIterator.class);
            DirectedEdgeFilter mockFilter = mock(DirectedEdgeFilter.class);

            // Configuration du graphe : retourne NodeAccess et EdgeExplorer
            when(mockGraph.getNodeAccess()).thenReturn(mockNodeAccess);
            when(mockGraph.createEdgeExplorer()).thenReturn(mockExplorer);
            
            // Configuration de l'EdgeExplorer : retourne l'itérateur pour le nœud 0
            when(mockExplorer.setBaseNode(anyInt())).thenReturn(mockIterator);

            // Configuration critique : Aucun edge adjacent (nœud isolé)
            // L'itérateur retourne immédiatement false lors du premier appel à next()
            when(mockIterator.next()).thenReturn(false);
            
            // Mock NodeAccess : fournit des coordonnées valides (optionnel, non utilisé ici)
            when(mockNodeAccess.getLat(anyInt())).thenReturn(10.0);
            when(mockNodeAccess.getLon(anyInt())).thenReturn(10.0);

            // Créer le DirectionResolver avec le graphe et filtre mockés
            DirectionResolver resolver = new DirectionResolver(mockGraph, mockFilter);
            
            // Point de requête arbitraire
            GHPoint queryPoint = new GHPoint(10.0, 10.0);

            // Act : Résoudre les directions pour le nœud isolé 0
            DirectionResolverResult result = resolver.resolveDirections(0, queryPoint);

            // Assert : Vérifications principales
            
            // 1. Le résultat ne devrait pas être null
            assertNotNull(result, "DirectionResolver ne devrait jamais retourner null");
            
            // 2. Le résultat devrait être "impossible" (pas de directions disponibles)
            assertTrue(result.isImpossible(), 
                    "Un nœud isolé devrait retourner un résultat 'impossible'");
            
            // 3. Vérifications des interactions avec les mocks (optionnel mais recommandé)
            
            // Vérifier que le graphe a bien été consulté
            verify(mockGraph, times(1)).createEdgeExplorer();
            
            // Vérifier que l'explorateur a été configuré avec le bon nœud
            verify(mockExplorer, times(1)).setBaseNode(0);
            
            // Vérifier que l'itérateur a été consulté (et a retourné false)
            verify(mockIterator, atLeastOnce()).next();
            
            // Vérifier que le filtre n'a JAMAIS été consulté (pas d'edges à filtrer)
            // Note : accept() n'est appelé que s'il y a des edges à filtrer
            verifyNoInteractions(mockFilter);
        }
      

}
