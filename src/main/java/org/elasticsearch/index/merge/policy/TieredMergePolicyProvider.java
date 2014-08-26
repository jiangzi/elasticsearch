/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.elasticsearch.index.merge.policy;

import org.apache.lucene.index.TieredMergePolicy;
import org.elasticsearch.ElasticsearchException;
import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.unit.ByteSizeUnit;
import org.elasticsearch.common.unit.ByteSizeValue;
import org.elasticsearch.index.settings.IndexSettingsService;
import org.elasticsearch.index.store.Store;

public class TieredMergePolicyProvider extends AbstractMergePolicyProvider<TieredMergePolicy> {

    private final IndexSettingsService indexSettingsService;
    private final ApplySettings applySettings = new ApplySettings();
    private TieredMergePolicy mergePolicy = new TieredMergePolicy();

    public static final double          DEFAULT_EXPUNGE_DELETES_ALLOWED     = 10d;
    public static final ByteSizeValue   DEFAULT_FLOOR_SEGMENT               = new ByteSizeValue(2, ByteSizeUnit.MB);
    public static final int             DEFAULT_MAX_MERGE_AT_ONCE           = 10;
    public static final int             DEFAULT_MAX_MERGE_AT_ONCE_EXPLICIT  = 30;
    public static final ByteSizeValue   DEFAULT_MAX_MERGED_SEGMENT          = new ByteSizeValue(5, ByteSizeUnit.GB);
    public static final double          DEFAULT_SEGMENTS_PER_TIER           = 10.0d;
    public static final double          DEFAULT_RECLAIM_DELETES_WEIGHT      = 2.0d;

    @Inject
    public TieredMergePolicyProvider(Store store, IndexSettingsService indexSettingsService) {
        super(store);
        this.indexSettingsService = indexSettingsService;

        double forceMergeDeletesPctAllowed = componentSettings.getAsDouble("expunge_deletes_allowed", DEFAULT_EXPUNGE_DELETES_ALLOWED); // percentage
        ByteSizeValue floorSegment = componentSettings.getAsBytesSize("floor_segment", DEFAULT_FLOOR_SEGMENT);
        int maxMergeAtOnce = componentSettings.getAsInt("max_merge_at_once", DEFAULT_MAX_MERGE_AT_ONCE);
        int maxMergeAtOnceExplicit = componentSettings.getAsInt("max_merge_at_once_explicit", DEFAULT_MAX_MERGE_AT_ONCE_EXPLICIT);
        // TODO is this really a good default number for max_merge_segment, what happens for large indices, won't they end up with many segments?
        ByteSizeValue maxMergedSegment = componentSettings.getAsBytesSize("max_merged_segment", DEFAULT_MAX_MERGED_SEGMENT);
        double segmentsPerTier = componentSettings.getAsDouble("segments_per_tier", DEFAULT_SEGMENTS_PER_TIER);
        double reclaimDeletesWeight = componentSettings.getAsDouble("reclaim_deletes_weight", DEFAULT_RECLAIM_DELETES_WEIGHT);

        maxMergeAtOnce = adjustMaxMergeAtOnceIfNeeded(maxMergeAtOnce, segmentsPerTier);
        mergePolicy.setNoCFSRatio(noCFSRatio);
        mergePolicy.setForceMergeDeletesPctAllowed(forceMergeDeletesPctAllowed);
        mergePolicy.setFloorSegmentMB(floorSegment.mbFrac());
        mergePolicy.setMaxMergeAtOnce(maxMergeAtOnce);
        mergePolicy.setMaxMergeAtOnceExplicit(maxMergeAtOnceExplicit);
        mergePolicy.setMaxMergedSegmentMB(maxMergedSegment.mbFrac());
        mergePolicy.setSegmentsPerTier(segmentsPerTier);
        mergePolicy.setReclaimDeletesWeight(reclaimDeletesWeight);
        logger.debug("using [tiered] merge mergePolicy with expunge_deletes_allowed[{}], floor_segment[{}], max_merge_at_once[{}], max_merge_at_once_explicit[{}], max_merged_segment[{}], segments_per_tier[{}], reclaim_deletes_weight[{}]",
                forceMergeDeletesPctAllowed, floorSegment, maxMergeAtOnce, maxMergeAtOnceExplicit, maxMergedSegment, segmentsPerTier, reclaimDeletesWeight);

        indexSettingsService.addListener(applySettings);
    }

    private int adjustMaxMergeAtOnceIfNeeded(int maxMergeAtOnce, double segmentsPerTier) {
        // fixing maxMergeAtOnce, see TieredMergePolicy#setMaxMergeAtOnce
        if (!(segmentsPerTier >= maxMergeAtOnce)) {
            int newMaxMergeAtOnce = (int) segmentsPerTier;
            // max merge at once should be at least 2
            if (newMaxMergeAtOnce <= 1) {
                newMaxMergeAtOnce = 2;
            }
            logger.debug("[tiered] merge mergePolicy changing max_merge_at_once from [{}] to [{}] because segments_per_tier [{}] has to be higher or equal to it", maxMergeAtOnce, newMaxMergeAtOnce, segmentsPerTier);
            maxMergeAtOnce = newMaxMergeAtOnce;
        }
        return maxMergeAtOnce;
    }

    @Override
    public TieredMergePolicy getMergePolicy() {
        return mergePolicy;
    }

    @Override
    public void close() throws ElasticsearchException {
        indexSettingsService.removeListener(applySettings);
    }

    public static final String INDEX_MERGE_POLICY_EXPUNGE_DELETES_ALLOWED = "index.merge.mergePolicy.expunge_deletes_allowed";
    public static final String INDEX_MERGE_POLICY_FLOOR_SEGMENT = "index.merge.mergePolicy.floor_segment";
    public static final String INDEX_MERGE_POLICY_MAX_MERGE_AT_ONCE = "index.merge.mergePolicy.max_merge_at_once";
    public static final String INDEX_MERGE_POLICY_MAX_MERGE_AT_ONCE_EXPLICIT = "index.merge.mergePolicy.max_merge_at_once_explicit";
    public static final String INDEX_MERGE_POLICY_MAX_MERGED_SEGMENT = "index.merge.mergePolicy.max_merged_segment";
    public static final String INDEX_MERGE_POLICY_SEGMENTS_PER_TIER = "index.merge.mergePolicy.segments_per_tier";
    public static final String INDEX_MERGE_POLICY_RECLAIM_DELETES_WEIGHT = "index.merge.mergePolicy.reclaim_deletes_weight";

    class ApplySettings implements IndexSettingsService.Listener {
        @Override
        public void onRefreshSettings(Settings settings) {
            double oldExpungeDeletesPctAllowed = mergePolicy.getForceMergeDeletesPctAllowed();
            double expungeDeletesPctAllowed = settings.getAsDouble(INDEX_MERGE_POLICY_EXPUNGE_DELETES_ALLOWED, DEFAULT_EXPUNGE_DELETES_ALLOWED);
            if (expungeDeletesPctAllowed != oldExpungeDeletesPctAllowed) {
                logger.info("updating [expunge_deletes_allowed] from [{}] to [{}]", oldExpungeDeletesPctAllowed, expungeDeletesPctAllowed);
                mergePolicy.setForceMergeDeletesPctAllowed(expungeDeletesPctAllowed);
            }

            double oldFloorSegmentMB = mergePolicy.getFloorSegmentMB();
            ByteSizeValue floorSegment = settings.getAsBytesSize(INDEX_MERGE_POLICY_FLOOR_SEGMENT, DEFAULT_FLOOR_SEGMENT);
            if (floorSegment.mbFrac() != oldFloorSegmentMB) {
                logger.info("updating [floor_segment] from [{}mb] to [{}]", oldFloorSegmentMB, floorSegment);
                mergePolicy.setFloorSegmentMB(floorSegment.mbFrac());
            }

            double oldSegmentsPerTier = mergePolicy.getSegmentsPerTier();
            double segmentsPerTier = settings.getAsDouble(INDEX_MERGE_POLICY_SEGMENTS_PER_TIER, DEFAULT_SEGMENTS_PER_TIER);
            if (segmentsPerTier != oldSegmentsPerTier) {
                logger.info("updating [segments_per_tier] from [{}] to [{}]", oldSegmentsPerTier, segmentsPerTier);
                mergePolicy.setSegmentsPerTier(segmentsPerTier);
            }

            int oldMaxMergeAtOnce = mergePolicy.getMaxMergeAtOnce();
            int maxMergeAtOnce = settings.getAsInt(INDEX_MERGE_POLICY_MAX_MERGE_AT_ONCE, DEFAULT_MAX_MERGE_AT_ONCE);
            if (maxMergeAtOnce != oldMaxMergeAtOnce) {
                logger.info("updating [max_merge_at_once] from [{}] to [{}]", oldMaxMergeAtOnce, maxMergeAtOnce);
                maxMergeAtOnce = adjustMaxMergeAtOnceIfNeeded(maxMergeAtOnce, segmentsPerTier);
                mergePolicy.setMaxMergeAtOnce(maxMergeAtOnce);
            }

            int oldMaxMergeAtOnceExplicit = mergePolicy.getMaxMergeAtOnceExplicit();
            int maxMergeAtOnceExplicit = settings.getAsInt(INDEX_MERGE_POLICY_MAX_MERGE_AT_ONCE_EXPLICIT, DEFAULT_MAX_MERGE_AT_ONCE_EXPLICIT);
            if (maxMergeAtOnceExplicit != oldMaxMergeAtOnceExplicit) {
                logger.info("updating [max_merge_at_once_explicit] from [{}] to [{}]", oldMaxMergeAtOnceExplicit, maxMergeAtOnceExplicit);
                mergePolicy.setMaxMergeAtOnceExplicit(maxMergeAtOnceExplicit);
            }

            double oldMaxMergedSegmentMB = mergePolicy.getMaxMergedSegmentMB();
            ByteSizeValue maxMergedSegment = settings.getAsBytesSize(INDEX_MERGE_POLICY_MAX_MERGED_SEGMENT, DEFAULT_MAX_MERGED_SEGMENT);
            if (maxMergedSegment.mbFrac() != oldMaxMergedSegmentMB) {
                logger.info("updating [max_merged_segment] from [{}mb] to [{}]", oldMaxMergedSegmentMB, maxMergedSegment);
                mergePolicy.setMaxMergedSegmentMB(maxMergedSegment.mbFrac());
            }

            double oldReclaimDeletesWeight = mergePolicy.getReclaimDeletesWeight();
            double reclaimDeletesWeight = settings.getAsDouble(INDEX_MERGE_POLICY_RECLAIM_DELETES_WEIGHT, DEFAULT_RECLAIM_DELETES_WEIGHT);
            if (reclaimDeletesWeight != oldReclaimDeletesWeight) {
                logger.info("updating [reclaim_deletes_weight] from [{}] to [{}]", oldReclaimDeletesWeight, reclaimDeletesWeight);
                mergePolicy.setReclaimDeletesWeight(reclaimDeletesWeight);
            }

            double noCFSRatio = parseNoCFSRatio(settings.get(INDEX_COMPOUND_FORMAT, Double.toString(TieredMergePolicyProvider.this.noCFSRatio)));
            if (noCFSRatio != TieredMergePolicyProvider.this.noCFSRatio) {
                logger.info("updating index.compound_format from [{}] to [{}]", formatNoCFSRatio(TieredMergePolicyProvider.this.noCFSRatio), formatNoCFSRatio(noCFSRatio));
                mergePolicy.setNoCFSRatio(noCFSRatio);
                TieredMergePolicyProvider.this.noCFSRatio = noCFSRatio;
            }
        }
    }
}