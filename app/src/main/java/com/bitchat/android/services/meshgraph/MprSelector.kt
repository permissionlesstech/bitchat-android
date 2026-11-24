package com.bitchat.android.services.meshgraph

/**
 * Pure, deterministic OLSR-style 2-hop Multipoint Relay (MPR) hub selector.
 *
 * Given a previous-hop node `P`, computes `N1(P)` (fresh confirmed 1-hop neighbors of `P`) and
 * `N2(P)` (fresh confirmed 2-hop neighbors reachable through `N1(P)` that are neither `P` nor in
 * `N1(P)`), then selects a deterministic subset of `N1(P)` that covers every node in `N2(P)` at
 * least `min(coverageK, availableCovers)` times.
 *
 * Tie-breaking is strictly deterministic (highest uncovered 2-hop reach -> highest confirmed
 * degree -> lexicographically smallest peerID) so all neighbors with the same 2-hop graph view
 * independently agree on the same MPR set without extra wire bytes.
 */
object MprSelector {

    enum class RelayDecisionReason {
        SELECTED_MPR,
        STALE_OR_MISSING_GRAPH,
        UNIDENTIFIED_DIRECT_NEIGHBOR,
        UNCONFIRMED_DIRECT_NEIGHBOR,
        SUPPRESSED_NON_MPR
    }

    data class RelayDecision(
        val shouldRelay: Boolean,
        val reason: RelayDecisionReason,
        val selectedMprs: Set<String> = emptySet()
    )

    /**
     * Select `k`-covered MPRs for [previousHopPeerID] using the fresh confirmed 2-hop graph in
     * [graphService].
     */
    fun selectMprs(
        previousHopPeerID: String,
        graphService: MeshGraphService = MeshGraphService.getInstance(),
        coverageK: Int = 1,
        maxAgeMs: Long = MeshGraphService.DEFAULT_EDGE_MAX_AGE_MS
    ): Set<String> {
        val neighborhood = graphService.freshTwoHopNeighborhood(previousHopPeerID, maxAgeMs)
        return selectMprs(neighborhood, coverageK)
    }

    /**
     * Pure selection over an explicit [MeshGraphService.TwoHopNeighborhood] snapshot.
     */
    fun selectMprs(
        neighborhood: MeshGraphService.TwoHopNeighborhood,
        coverageK: Int = 1
    ): Set<String> {
        require(coverageK >= 1) { "coverageK must be >= 1, was $coverageK" }
        if (!neighborhood.centerIsFresh || neighborhood.n1.isEmpty()) {
            return emptySet()
        }
        return selectMprsFromAdjacency(
            previousHopPeerID = neighborhood.centerPeerID,
            n1 = neighborhood.n1,
            neighborsByN1 = neighborhood.neighborsByN1,
            coverageK = coverageK
        ).selectedMprs
    }

    /**
     * Pure selection over explicit 1-hop and 2-hop neighbor maps (handy for tests and simulations).
     */
    fun selectMprs(
        previousHopPeerID: String,
        n1: Set<String>,
        neighborsByN1: Map<String, Set<String>>,
        coverageK: Int = 1
    ): Set<String> {
        require(coverageK >= 1) { "coverageK must be >= 1, was $coverageK" }
        val cleanN1 = n1.filterTo(LinkedHashSet()) { it != previousHopPeerID }
        if (cleanN1.isEmpty()) return emptySet()
        return selectMprsFromAdjacency(
            previousHopPeerID = previousHopPeerID,
            n1 = cleanN1,
            neighborsByN1 = neighborsByN1,
            coverageK = coverageK
        ).selectedMprs
    }

    /**
     * Evaluate whether [myPeerID] should relay a packet received from [previousHopPeerID] under
     * `k`-covered MPR selection with the mixed/moving-mesh fail-safe rule.
     *
     * Fail-safe rule:
     * - If the local graph does not have a fresh announcement for [previousHopPeerID], relay.
     * - If [myPeerID] is in `P`'s selected MPR set, relay.
     * - Even if [myPeerID] is NOT in `P`'s selected MPR set, relay if [myPeerID] has any
     *   unidentified direct link OR any currently connected direct neighbor (other than self,
     *   [previousHopPeerID], or [originPeerID]) that is neither a fresh confirmed neighbor of
     *   [previousHopPeerID] nor already covered at least [coverageK] times by the selected MPRs
     *   (e.g., a legacy client that omits TLV `0x04`, a newly joined peer, or a peer whose link
     *   to `P` is stale/unconfirmed).
     */
    fun evaluateRelay(
        myPeerID: String,
        previousHopPeerID: String,
        originPeerID: String? = null,
        graphService: MeshGraphService = MeshGraphService.getInstance(),
        directConnectedNeighbors: Set<String> = emptySet(),
        hasUnidentifiedDirectLink: Boolean = false,
        coverageK: Int = 1,
        maxAgeMs: Long = MeshGraphService.DEFAULT_EDGE_MAX_AGE_MS
    ): RelayDecision {
        val neighborhood = graphService.freshTwoHopNeighborhood(previousHopPeerID, maxAgeMs)
        return evaluateRelay(
            myPeerID = myPeerID,
            neighborhood = neighborhood,
            originPeerID = originPeerID,
            directConnectedNeighbors = directConnectedNeighbors,
            hasUnidentifiedDirectLink = hasUnidentifiedDirectLink,
            coverageK = coverageK
        )
    }

    /**
     * Pure relay evaluation over an explicit [MeshGraphService.TwoHopNeighborhood] snapshot.
     */
    fun evaluateRelay(
        myPeerID: String,
        neighborhood: MeshGraphService.TwoHopNeighborhood,
        originPeerID: String? = null,
        directConnectedNeighbors: Set<String> = emptySet(),
        hasUnidentifiedDirectLink: Boolean = false,
        coverageK: Int = 1
    ): RelayDecision {
        require(coverageK >= 1) { "coverageK must be >= 1, was $coverageK" }

        if (!neighborhood.centerIsFresh) {
            return RelayDecision(
                shouldRelay = true,
                reason = RelayDecisionReason.STALE_OR_MISSING_GRAPH
            )
        }

        val previousHopPeerID = neighborhood.centerPeerID
        val selection = selectMprsFromAdjacency(
            previousHopPeerID = previousHopPeerID,
            n1 = neighborhood.n1,
            neighborsByN1 = neighborhood.neighborsByN1,
            coverageK = coverageK
        )

        if (myPeerID in selection.selectedMprs) {
            return RelayDecision(
                shouldRelay = true,
                reason = RelayDecisionReason.SELECTED_MPR,
                selectedMprs = selection.selectedMprs
            )
        }

        if (hasUnidentifiedDirectLink) {
            return RelayDecision(
                shouldRelay = true,
                reason = RelayDecisionReason.UNIDENTIFIED_DIRECT_NEIGHBOR,
                selectedMprs = selection.selectedMprs
            )
        }

        val hasUncoveredDirectNeighbor = directConnectedNeighbors.any { neighbor ->
            neighbor != myPeerID &&
                neighbor != previousHopPeerID &&
                neighbor != originPeerID &&
                neighbor !in neighborhood.n1 &&
                (selection.coverageCountByN2[neighbor] ?: 0) < coverageK
        }
        if (hasUncoveredDirectNeighbor) {
            return RelayDecision(
                shouldRelay = true,
                reason = RelayDecisionReason.UNCONFIRMED_DIRECT_NEIGHBOR,
                selectedMprs = selection.selectedMprs
            )
        }

        return RelayDecision(
            shouldRelay = false,
            reason = RelayDecisionReason.SUPPRESSED_NON_MPR,
            selectedMprs = selection.selectedMprs
        )
    }

    private data class SelectionResult(
        val selectedMprs: Set<String>,
        val coverageCountByN2: Map<String, Int>
    )

    private fun selectMprsFromAdjacency(
        previousHopPeerID: String,
        n1: Set<String>,
        neighborsByN1: Map<String, Set<String>>,
        coverageK: Int
    ): SelectionResult {
        val strictN2ByU = LinkedHashMap<String, Set<String>>(n1.size)
        val degreeByU = LinkedHashMap<String, Int>(n1.size)
        val coversByW = LinkedHashMap<String, MutableList<String>>()

        // Sort N1 deterministically so iteration order is independent of caller map order.
        val sortedN1 = n1.sorted()
        for (u in sortedN1) {
            val uNeighbors = neighborsByN1[u].orEmpty()
            degreeByU[u] = uNeighbors.size
            val strictN2 = LinkedHashSet<String>()
            for (v in uNeighbors) {
                if (v != previousHopPeerID && v !in n1 && v != u) {
                    strictN2.add(v)
                    coversByW.getOrPut(v) { mutableListOf() }.add(u)
                }
            }
            strictN2ByU[u] = strictN2
        }

        if (coversByW.isEmpty()) {
            return SelectionResult(emptySet(), emptyMap())
        }

        val selectedMprs = LinkedHashSet<String>()
        val requiredCoverageByW = LinkedHashMap<String, Int>(coversByW.size)
        val coverageCountByW = LinkedHashMap<String, Int>(coversByW.size)

        // Step 1: Include any u in N1(P) that is the sole cover (or among the only <= k covers)
        // for some 2-hop node w in N2(P).
        for ((w, covers) in coversByW) {
            requiredCoverageByW[w] = minOf(coverageK, covers.size)
            coverageCountByW[w] = 0
            if (covers.size <= coverageK) {
                selectedMprs.addAll(covers)
            }
        }

        for (u in selectedMprs) {
            for (w in strictN2ByU[u].orEmpty()) {
                coverageCountByW[w] = (coverageCountByW[w] ?: 0) + 1
            }
        }

        // Step 2: Greedily select the remaining u in N1(P) covering the most still-uncovered
        // nodes in N2(P), with strict tie-breaking:
        //   1. highest uncovered 2-hop reach
        //   2. highest confirmed 1-hop degree
        //   3. lexicographically smallest peerID
        val remainingCandidates = sortedN1.filterTo(LinkedHashSet()) { it !in selectedMprs }
        while (remainingCandidates.isNotEmpty()) {
            val uncoveredN2 = HashSet<String>()
            for ((w, required) in requiredCoverageByW) {
                if ((coverageCountByW[w] ?: 0) < required) {
                    uncoveredN2.add(w)
                }
            }
            if (uncoveredN2.isEmpty()) break

            var bestCandidate: String? = null
            var bestReach = 0
            var bestDegree = -1

            for (u in remainingCandidates) {
                var reach = 0
                for (w in strictN2ByU[u].orEmpty()) {
                    if (w in uncoveredN2) reach++
                }
                if (reach == 0) continue

                val degree = degreeByU[u] ?: 0
                val isBetter = when {
                    bestCandidate == null -> true
                    reach != bestReach -> reach > bestReach
                    degree != bestDegree -> degree > bestDegree
                    else -> u < bestCandidate
                }
                if (isBetter) {
                    bestCandidate = u
                    bestReach = reach
                    bestDegree = degree
                }
            }

            if (bestCandidate == null) break

            selectedMprs.add(bestCandidate)
            remainingCandidates.remove(bestCandidate)
            for (w in strictN2ByU[bestCandidate].orEmpty()) {
                coverageCountByW[w] = (coverageCountByW[w] ?: 0) + 1
            }
        }

        return SelectionResult(
            selectedMprs = selectedMprs,
            coverageCountByN2 = coverageCountByW
        )
    }
}
