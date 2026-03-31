<template>
  <section class="graph-panel" :class="{ 'graph-panel-ready': hasGraph }">
    <div class="graph-panel-header">
      <div class="graph-panel-copy">
        <div class="graph-kicker">Knowledge graph</div>
        <div class="graph-title">{{ titleText }}</div>
        <div class="graph-subtitle">{{ subtitleText }}</div>
      </div>

      <div class="graph-header-actions">
        <button
          type="button"
          class="graph-toggle"
          :aria-expanded="expanded"
          @click="toggleExpanded"
        >
          <span>{{ expanded ? 'Hide graph' : 'Show graph' }}</span>
          <span class="graph-toggle-chevron" :class="{ 'graph-toggle-chevron-open': expanded }">⌄</span>
        </button>

        <div class="graph-stats">
          <div class="graph-stat-chip">
            <span class="graph-stat-value">{{ matchedStat }}</span>
            <span class="graph-stat-label">hits</span>
          </div>
          <div class="graph-stat-chip">
            <span class="graph-stat-value">{{ nodeStat }}</span>
            <span class="graph-stat-label">nodes</span>
          </div>
          <div class="graph-stat-chip">
            <span class="graph-stat-value">{{ edgeStat }}</span>
            <span class="graph-stat-label">links</span>
          </div>
        </div>
      </div>
    </div>

    <div v-if="!expanded" class="graph-collapsed">
      <div class="graph-collapsed-title">Expand to load the entity graph for this answer.</div>
      <div class="graph-collapsed-body">
        The knowledge graph query is deferred until this section is opened, so collapsed answers do not trigger KG requests.
      </div>
    </div>

    <a-spin v-else :spinning="loading">
      <template v-if="hasGraph">
        <div class="graph-layout">
          <div class="graph-canvas-card">
            <div class="graph-canvas-header">
              <div class="graph-section-label graph-section-label-compact">Entity graph</div>
              <div class="graph-legend">
                <span
                  v-for="legend in typeLegend"
                  :key="legend.type"
                  class="graph-legend-item"
                  :style="legend.style"
                >
                  <span class="graph-legend-dot"></span>
                  <span>{{ legend.label }}</span>
                </span>
              </div>
            </div>

            <div class="graph-canvas-shell">
              <svg
                class="graph-canvas"
                viewBox="0 0 720 440"
                role="img"
                aria-label="Question entity knowledge graph"
                @mouseleave="clearHover"
              >
                <defs>
                  <marker
                    :id="edgeMarkerId"
                    markerWidth="10"
                    markerHeight="10"
                    refX="8"
                    refY="5"
                    orient="auto"
                    markerUnits="strokeWidth"
                  >
                    <path d="M 0 0 L 10 5 L 0 10 z" fill="#94a3b8" />
                  </marker>
                </defs>

                <g class="graph-edge-layer">
                  <g
                    v-for="edge in positionedEdges"
                    :key="edge.id"
                    class="graph-edge-group"
                    :class="{
                      'graph-edge-active': isEdgeActive(edge),
                      'graph-edge-muted': isEdgeMuted(edge),
                    }"
                  >
                    <line
                      :x1="edge.source.x"
                      :y1="edge.source.y"
                      :x2="edge.target.x"
                      :y2="edge.target.y"
                      class="graph-edge-line"
                      :marker-end="`url(#${edgeMarkerId})`"
                    />
                    <g
                      v-if="showEdgeLabel(edge)"
                      :transform="`translate(${edge.labelX}, ${edge.labelY})`"
                      class="graph-edge-label-group"
                    >
                      <rect
                        :width="edge.badgeWidth"
                        height="24"
                        rx="12"
                        x="0"
                        y="-12"
                        class="graph-edge-badge"
                      />
                      <text x="10" y="4" class="graph-edge-text">{{ edge.shortLabel }}</text>
                    </g>
                  </g>
                </g>

                <g class="graph-node-layer">
                  <g
                    v-for="node in positionedNodes"
                    :key="node.id"
                    class="graph-node-group"
                    :class="{
                      'graph-node-active': isNodeActive(node.id),
                      'graph-node-muted': isNodeMuted(node.id),
                      'graph-node-matched': node.matched,
                    }"
                    :transform="`translate(${node.x}, ${node.y})`"
                    tabindex="0"
                    role="button"
                    :aria-label="`${node.label}, ${node.entityType}`"
                    @mouseenter="setHoveredNode(node.id)"
                    @focus="setHoveredNode(node.id)"
                    @blur="clearHover"
                  >
                    <circle
                      :r="node.radius + 14"
                      class="graph-node-halo"
                      :style="{ fill: node.palette.halo }"
                    />
                    <circle
                      v-if="node.matched"
                      :r="node.radius + 7"
                      class="graph-node-match-ring"
                      :style="{ stroke: node.palette.stroke }"
                    />
                    <circle
                      :r="node.radius"
                      class="graph-node-core"
                      :style="{ fill: node.palette.surface, stroke: node.palette.stroke }"
                    />
                    <circle
                      :r="node.radius - 7"
                      class="graph-node-inner"
                      :style="{ fill: node.palette.inner }"
                    />
                    <text y="-4" class="graph-node-label">{{ node.shortLabel }}</text>
                    <text y="16" class="graph-node-meta">{{ node.shortType }}</text>
                    <title>{{ `${node.label}${node.entityType ? ` - ${node.entityType}` : ''}` }}</title>
                  </g>
                </g>
              </svg>

            </div>

            <div v-if="hoveredNode" class="graph-hover-card">
                <div class="graph-hover-type" :style="hoveredNodePaletteStyle">
                  {{ formatType(hoveredNode.entityType) }}
                </div>
                <div class="graph-hover-title">{{ hoveredNode.label }}</div>
                <div class="graph-hover-subtitle">
                  {{ hoveredNode.matched ? 'Matched entity in the question' : 'Linked entity from the graph' }}
                </div>
                <div class="graph-hover-metrics">
                  <span>{{ hoveredRelations.length }} relations</span>
                  <span>{{ hoveredNode.degree }} degree</span>
                </div>
                <div class="graph-hover-relations">
                  <div
                    v-for="relation in hoveredRelations"
                    :key="relation.id"
                    class="graph-hover-relation"
                  >
                    <span class="graph-hover-relation-type">{{ relation.relationType }}</span>
                    <span class="graph-hover-relation-node">{{ relation.otherLabel }}</span>
                  </div>
                </div>
            </div>
          </div>

          <div class="graph-side">
            <div class="graph-side-card">
              <div class="graph-section-label">Entity types</div>
              <div class="graph-chip-row">
                <span
                  v-for="legend in typeLegend"
                  :key="legend.type"
                  class="graph-chip graph-chip-type"
                  :style="legend.style"
                >
                  {{ legend.label }}
                </span>
              </div>
            </div>

            <div class="graph-side-card">
              <div class="graph-section-label">Hover details</div>
              <div v-if="hoveredNode" class="graph-insight-list">
                <div class="graph-insight-item">
                  <span class="graph-insight-key">Entity</span>
                  <span class="graph-insight-value">{{ hoveredNode.label }}</span>
                </div>
                <div class="graph-insight-item">
                  <span class="graph-insight-key">Type</span>
                  <span class="graph-insight-value">{{ formatType(hoveredNode.entityType) }}</span>
                </div>
                <div class="graph-insight-item">
                  <span class="graph-insight-key">Relations</span>
                  <span class="graph-insight-value">{{ hoveredRelationText }}</span>
                </div>
              </div>
              <div v-else class="graph-empty-tip">
                Hover a node to inspect its connected entities and relation types.
              </div>
            </div>

            <div class="graph-side-card">
              <div class="graph-section-label">Matched entities</div>
              <div class="graph-chip-row">
                <span
                  v-for="entity in matchedEntityNodes"
                  :key="entity.id"
                  class="graph-chip graph-chip-hit"
                  :style="entity.palette.badgeStyle"
                >
                  {{ entity.label }}
                </span>
              </div>
            </div>

            <div v-if="graphData?.papers.length" class="graph-side-card">
              <div class="graph-section-label">Supporting papers</div>
              <div class="graph-paper-list">
                <div v-for="paper in graphData?.papers ?? []" :key="paper" class="graph-paper-card">
                  {{ paper }}
                </div>
              </div>
            </div>
          </div>
        </div>
      </template>

      <div v-else class="graph-state">
        <div class="graph-state-kicker">{{ stateKicker }}</div>
        <div class="graph-state-title">{{ emptyText }}</div>
        <div class="graph-state-body">{{ stateBody }}</div>
      </div>
    </a-spin>
  </section>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue';

import { kgService } from '@/services/kg';
import type { QuestionGraphEdge, QuestionGraphNode, QuestionGraphView } from '@/types/kg';

const props = defineProps<{
  prompt: string;
}>();

type PositionedNode = QuestionGraphNode & {
  x: number;
  y: number;
  radius: number;
  shortLabel: string;
  shortType: string;
  palette: EntityPalette;
};

type PositionedEdge = QuestionGraphEdge & {
  source: PositionedNode;
  target: PositionedNode;
  labelX: number;
  labelY: number;
  shortLabel: string;
  badgeWidth: number;
};

type EntityPalette = {
  fill: string;
  surface: string;
  inner: string;
  stroke: string;
  halo: string;
  badgeStyle: Record<string, string>;
};

type HoverRelation = {
  id: string;
  relationType: string;
  otherLabel: string;
};

const TYPE_PALETTE_PRESETS = [
  {
    fill: '#f97316',
    surface: 'rgba(249, 115, 22, 0.18)',
    inner: '#fff7ed',
    stroke: '#ea580c',
    halo: 'rgba(249, 115, 22, 0.18)',
  },
  {
    fill: '#2563eb',
    surface: 'rgba(37, 99, 235, 0.18)',
    inner: '#eff6ff',
    stroke: '#1d4ed8',
    halo: 'rgba(37, 99, 235, 0.16)',
  },
  {
    fill: '#0f766e',
    surface: 'rgba(15, 118, 110, 0.18)',
    inner: '#f0fdfa',
    stroke: '#0f766e',
    halo: 'rgba(15, 118, 110, 0.16)',
  },
  {
    fill: '#7c3aed',
    surface: 'rgba(124, 58, 237, 0.18)',
    inner: '#f5f3ff',
    stroke: '#6d28d9',
    halo: 'rgba(124, 58, 237, 0.16)',
  },
  {
    fill: '#be123c',
    surface: 'rgba(190, 18, 60, 0.18)',
    inner: '#fff1f2',
    stroke: '#be123c',
    halo: 'rgba(190, 18, 60, 0.16)',
  },
  {
    fill: '#0891b2',
    surface: 'rgba(8, 145, 178, 0.18)',
    inner: '#ecfeff',
    stroke: '#0e7490',
    halo: 'rgba(8, 145, 178, 0.16)',
  },
] as const;

const graphCache = new Map<string, QuestionGraphView>();

const loading = ref(false);
const expanded = ref(false);
const graphData = ref<QuestionGraphView | null>(null);
const errorText = ref('');
const hoveredNodeId = ref('');
const edgeMarkerId = `graph-edge-marker-${Math.random().toString(36).slice(2, 10)}`;

const hasGraph = computed(
  () => Boolean(graphData.value && graphData.value.status === 'READY' && graphData.value.nodes.length > 0),
);
const matchedNodes = computed(() => (graphData.value?.nodes ?? []).filter((node) => node.matched));
const relatedNodes = computed(() => (graphData.value?.nodes ?? []).filter((node) => !node.matched));

const typePaletteMap = computed(() => {
  const paletteMap = new Map<string, EntityPalette>();

  for (const node of graphData.value?.nodes ?? []) {
    const normalizedType = normalizeEntityType(node.entityType);
    if (paletteMap.has(normalizedType)) {
      continue;
    }

    const preset =
      TYPE_PALETTE_PRESETS[hashString(normalizedType) % TYPE_PALETTE_PRESETS.length] ?? TYPE_PALETTE_PRESETS[0];
    paletteMap.set(normalizedType, buildPalette(preset));
  }

  return paletteMap;
});

const titleText = computed(() => {
  if (!expanded.value && !loading.value && !graphData.value) {
    return 'Knowledge graph ready to load';
  }

  if (!graphData.value?.matchedEntities?.length) {
    return 'Entity relationships not surfaced yet';
  }

  return `${graphData.value.matchedEntities.length} entity hit${
    graphData.value.matchedEntities.length > 1 ? 's' : ''
  } for this answer`;
});

const subtitleText = computed(() => {
  if (!expanded.value && !loading.value && !graphData.value) {
    return 'Expand this section to fetch the matched entities and relationships for the current answer.';
  }

  if (loading.value) {
    return 'Querying the Neo4j knowledge graph for matched entities and one-hop relations.';
  }

  if (hasGraph.value) {
    return 'Nodes represent entities, edges represent relationships, and colors encode entity types.';
  }

  if (graphData.value?.status === 'UNAVAILABLE') {
    return 'The graph service is currently unavailable, so only the textual answer is shown.';
  }

  return 'No matched entity neighborhood was returned for this question.';
});

const matchedStat = computed(() => {
  if (!expanded.value && !graphData.value && !loading.value) {
    return '--';
  }
  return String(graphData.value?.matchedEntities.length ?? 0);
});

const nodeStat = computed(() => {
  if (!expanded.value && !graphData.value && !loading.value) {
    return '--';
  }
  return String(graphData.value?.nodes.length ?? 0);
});

const edgeStat = computed(() => {
  if (!expanded.value && !graphData.value && !loading.value) {
    return '--';
  }
  return String(graphData.value?.edges.length ?? 0);
});

const typeLegend = computed(() =>
  Array.from(typePaletteMap.value.entries()).map(([type, palette]) => ({
    type,
    label: formatType(type),
    style: palette.badgeStyle,
  })),
);

const nodeConnectionMap = computed(() => {
  const map = new Map<string, { nodeIds: Set<string>; edgeIds: Set<string> }>();

  for (const node of graphData.value?.nodes ?? []) {
    map.set(node.id, { nodeIds: new Set([node.id]), edgeIds: new Set() });
  }

  for (const edge of graphData.value?.edges ?? []) {
    map.get(edge.source)?.nodeIds.add(edge.target);
    map.get(edge.source)?.edgeIds.add(edge.id);
    map.get(edge.target)?.nodeIds.add(edge.source);
    map.get(edge.target)?.edgeIds.add(edge.id);
  }

  return map;
});

const positionedNodes = computed<PositionedNode[]>(() => {
  const nodes = new Map<string, PositionedNode>();
  const centerX = 360;
  const centerY = 220;
  const matched = matchedNodes.value;
  const related = relatedNodes.value;

  const [firstMatched] = matched;

  if (matched.length === 1 && firstMatched) {
    nodes.set(firstMatched.id, createPositionedNode(firstMatched, centerX, centerY, 36));
  } else {
    matched.forEach((node, index) => {
      const angle = (-Math.PI / 2) + ((Math.PI * 2) / Math.max(matched.length, 1)) * index;
      nodes.set(
        node.id,
        createPositionedNode(
          node,
          centerX + Math.cos(angle) * 142,
          centerY + Math.sin(angle) * 112,
          34,
        ),
      );
    });
  }

  const groupMap = new Map<string, QuestionGraphNode[]>();
  for (const node of related) {
    const connectedMatched = (graphData.value?.edges ?? [])
      .filter((edge) => edge.source === node.id || edge.target === node.id)
      .map((edge) => (edge.source === node.id ? edge.target : edge.source))
      .filter((otherId) => matched.some((candidate) => candidate.id === otherId));

    const anchorKey = connectedMatched[0] ?? node.id;
    const bucket = groupMap.get(anchorKey) ?? [];
    bucket.push(node);
    groupMap.set(anchorKey, bucket);
  }

  for (const [anchorId, bucket] of groupMap.entries()) {
    const anchor = nodes.get(anchorId);
    bucket.forEach((node, index) => {
      const baseAngle = anchor
        ? Math.atan2(anchor.y - centerY, anchor.x - centerX)
        : (-Math.PI / 2) + ((Math.PI * 2) / Math.max(related.length, 1)) * index;
      const spread = bucket.length === 1 ? 0 : (index - (bucket.length - 1) / 2) * 0.34;
      const angle = baseAngle + spread;
      nodes.set(
        node.id,
        createPositionedNode(
          node,
          centerX + Math.cos(angle) * 292,
          centerY + Math.sin(angle) * 188,
          24,
        ),
      );
    });
  }

  const positioned = Array.from(nodes.values());
  applyNodeSeparation(positioned, { minGap: 22, width: 720, height: 440 });
  return positioned;
});

const positionedNodeMap = computed(() => new Map(positionedNodes.value.map((node) => [node.id, node])));

const positionedEdges = computed<PositionedEdge[]>(() =>
  (graphData.value?.edges ?? [])
    .map((edge) => {
      const source = positionedNodeMap.value.get(edge.source);
      const target = positionedNodeMap.value.get(edge.target);
      if (!source || !target) {
        return null;
      }

      const shortLabel = truncate(formatType(edge.relationType), 18);
      return {
        ...edge,
        source,
        target,
        labelX: (source.x + target.x) / 2 + 6,
        labelY: (source.y + target.y) / 2 - 10,
        shortLabel,
        badgeWidth: Math.max(78, shortLabel.length * 8 + 18),
      };
    })
    .filter((edge): edge is PositionedEdge => Boolean(edge)),
);

const matchedEntityNodes = computed(() =>
  matchedNodes.value.map((node) => ({
    ...node,
    palette: getPalette(node.entityType),
  })),
);

const hoveredNode = computed(() => {
  if (!hoveredNodeId.value) {
    return null;
  }
  return positionedNodeMap.value.get(hoveredNodeId.value) ?? null;
});

const hoveredRelations = computed<HoverRelation[]>(() => {
  if (!hoveredNode.value) {
    return [];
  }

  return positionedEdges.value
    .filter((edge) => edge.source.id === hoveredNode.value?.id || edge.target.id === hoveredNode.value?.id)
    .map((edge) => ({
      id: edge.id,
      relationType: formatType(edge.relationType),
      otherLabel: edge.source.id === hoveredNode.value?.id ? edge.target.label : edge.source.label,
    }));
});

const hoveredRelationText = computed(() => {
  if (!hoveredRelations.value.length) {
    return 'No visible relations';
  }

  return hoveredRelations.value
    .map((relation) => `${relation.relationType} -> ${relation.otherLabel}`)
    .join(', ');
});

const hoveredNodePaletteStyle = computed(() => hoveredNode.value?.palette.badgeStyle ?? {});

const emptyText = computed(() => {
  if (errorText.value) {
    return errorText.value;
  }
  if (graphData.value?.status === 'UNAVAILABLE') {
    return 'Knowledge graph is unavailable';
  }
  return 'No entity graph matched this question yet';
});

const stateKicker = computed(() => {
  if (loading.value) {
    return 'Graph loading';
  }
  if (graphData.value?.status === 'UNAVAILABLE' || errorText.value) {
    return 'Graph unavailable';
  }
  return 'Graph pending';
});

const stateBody = computed(() => {
  if (loading.value) {
    return 'The panel stays attached to the answer and will render automatically once the query returns.';
  }
  if (graphData.value?.status === 'UNAVAILABLE' || errorText.value) {
    return 'Check the KG query service or Neo4j runtime if this should be available for the current environment.';
  }
  return 'Try a question that directly includes entity names present in the knowledge graph.';
});

const fetchGraph = async () => {
  const prompt = props.prompt.trim();
  if (!prompt) {
    graphData.value = null;
    errorText.value = 'Question is empty';
    return;
  }

  const cached = graphCache.get(prompt);
  if (cached) {
    graphData.value = cached;
    errorText.value = '';
    hoveredNodeId.value = cached.nodes.find((node) => node.matched)?.id ?? '';
    return;
  }

  loading.value = true;
  errorText.value = '';

  try {
    const response = await kgService.queryQuestionGraph(prompt);
    graphCache.set(prompt, response);
    graphData.value = response;
    hoveredNodeId.value = response.nodes.find((node) => node.matched)?.id ?? '';
  } catch (error) {
    console.error('Failed to load question graph', error);
    errorText.value = 'Failed to load graph data';
    graphData.value = null;
    hoveredNodeId.value = '';
  } finally {
    loading.value = false;
  }
};

watch(
  () => props.prompt,
  () => {
    const prompt = props.prompt.trim();
    const cached = prompt ? graphCache.get(prompt) ?? null : null;
    graphData.value = cached;
    errorText.value = '';
    hoveredNodeId.value = cached?.nodes.find((node) => node.matched)?.id ?? '';

    if (expanded.value && prompt && !cached && !loading.value) {
      void fetchGraph();
    }
  },
  { immediate: true },
);

function toggleExpanded() {
  expanded.value = !expanded.value;

  if (expanded.value && !graphData.value && !loading.value) {
    void fetchGraph();
  }
}

function truncate(value: string, maxLength: number) {
  if (!value) {
    return '';
  }
  if (value.length <= maxLength) {
    return value;
  }
  return `${value.slice(0, Math.max(1, maxLength - 3))}...`;
}

function createPositionedNode(node: QuestionGraphNode, x: number, y: number, radius: number): PositionedNode {
  return {
    ...node,
    x,
    y,
    radius,
    shortLabel: truncate(node.label, radius >= 34 ? 16 : 14),
    shortType: truncate(formatType(node.entityType), radius >= 34 ? 14 : 12),
    palette: getPalette(node.entityType),
  };
}

function applyNodeSeparation(
  nodes: PositionedNode[],
  bounds: { minGap: number; width: number; height: number },
) {
  for (let pass = 0; pass < 8; pass += 1) {
    for (let index = 0; index < nodes.length; index += 1) {
      for (let otherIndex = index + 1; otherIndex < nodes.length; otherIndex += 1) {
        const node = nodes[index];
        const other = nodes[otherIndex];
        if (!node || !other) {
          continue;
        }
        const minDistance = node.radius + other.radius + bounds.minGap;
        const dx = other.x - node.x;
        const dy = other.y - node.y;
        const distance = Math.hypot(dx, dy) || 1;

        if (distance >= minDistance) {
          continue;
        }

        const overlap = (minDistance - distance) / 2;
        const offsetX = (dx / distance) * overlap;
        const offsetY = (dy / distance) * overlap;

        node.x -= offsetX;
        node.y -= offsetY;
        other.x += offsetX;
        other.y += offsetY;
      }

      const current = nodes[index];
      if (!current) {
        continue;
      }
      current.x = Math.min(Math.max(current.x, current.radius + 22), bounds.width - current.radius - 22);
      current.y = Math.min(Math.max(current.y, current.radius + 26), bounds.height - current.radius - 24);
    }
  }
}

function buildPalette(preset: (typeof TYPE_PALETTE_PRESETS)[number]): EntityPalette {
  return {
    fill: preset.fill,
    surface: preset.surface,
    inner: preset.inner,
    stroke: preset.stroke,
    halo: preset.halo,
    badgeStyle: {
      color: preset.stroke,
      background: preset.surface,
      border: `1px solid ${preset.halo}`,
      '--legend-fill': preset.fill,
    },
  };
}

function getPalette(entityType: string) {
  const fallbackPreset = TYPE_PALETTE_PRESETS[0];
  return (
    typePaletteMap.value.get(normalizeEntityType(entityType)) ??
    buildPalette(fallbackPreset)
  );
}

function setHoveredNode(nodeId: string) {
  hoveredNodeId.value = nodeId;
}

function clearHover() {
  hoveredNodeId.value = '';
}

function isNodeActive(nodeId: string) {
  if (!hoveredNodeId.value) {
    return true;
  }
  return nodeConnectionMap.value.get(hoveredNodeId.value)?.nodeIds.has(nodeId) ?? false;
}

function isNodeMuted(nodeId: string) {
  if (!hoveredNodeId.value) {
    return false;
  }
  return !isNodeActive(nodeId);
}

function isEdgeActive(edge: PositionedEdge) {
  if (!hoveredNodeId.value) {
    return false;
  }
  return nodeConnectionMap.value.get(hoveredNodeId.value)?.edgeIds.has(edge.id) ?? false;
}

function isEdgeMuted(edge: PositionedEdge) {
  if (!hoveredNodeId.value) {
    return false;
  }
  return !isEdgeActive(edge);
}

function showEdgeLabel(edge: PositionedEdge) {
  if (!hoveredNodeId.value) {
    return edge.source.matched || edge.target.matched;
  }
  return isEdgeActive(edge);
}

function normalizeEntityType(value: string) {
  return value?.trim().toLowerCase() || 'entity';
}

function formatType(value: string) {
  if (!value) {
    return 'Entity';
  }
  return value
    .toLowerCase()
    .split(/[_\s-]+/)
    .filter(Boolean)
    .map((segment) => segment.slice(0, 1).toUpperCase() + segment.slice(1))
    .join(' ');
}

function hashString(value: string) {
  return Array.from(value).reduce((hash, char) => {
    return ((hash << 5) - hash + char.charCodeAt(0)) >>> 0;
  }, 0);
}
</script>

<style scoped>
.graph-panel {
  position: relative;
  overflow: hidden;
  border: 1px solid rgba(148, 163, 184, 0.22);
  border-radius: 24px;
  background:
    radial-gradient(circle at top left, rgba(14, 165, 233, 0.12), transparent 34%),
    radial-gradient(circle at top right, rgba(249, 115, 22, 0.12), transparent 30%),
    linear-gradient(180deg, #f8fbff 0%, #ffffff 48%, #f7fafc 100%);
  box-shadow:
    0 18px 38px rgba(15, 23, 42, 0.08),
    inset 0 1px 0 rgba(255, 255, 255, 0.9);
}

.graph-panel::before {
  content: '';
  position: absolute;
  inset: 0;
  background-image:
    linear-gradient(rgba(148, 163, 184, 0.06) 1px, transparent 1px),
    linear-gradient(90deg, rgba(148, 163, 184, 0.06) 1px, transparent 1px);
  background-size: 24px 24px;
  mask-image: linear-gradient(180deg, rgba(255, 255, 255, 0.46), transparent 72%);
  pointer-events: none;
}

.graph-panel-header,
.graph-layout,
.graph-state {
  position: relative;
  z-index: 1;
}

.graph-panel-header {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 20px;
  padding: 20px 22px 0;
}

.graph-header-actions {
  display: flex;
  flex-direction: column;
  align-items: flex-end;
  gap: 12px;
}

.graph-panel-copy {
  max-width: 32rem;
}

.graph-kicker {
  margin-bottom: 6px;
  font-size: 11px;
  font-weight: 700;
  letter-spacing: 0.16em;
  text-transform: uppercase;
  color: #9a3412;
}

.graph-title {
  font-size: 24px;
  font-weight: 700;
  color: #0f172a;
}

.graph-subtitle {
  margin-top: 6px;
  font-size: 13px;
  line-height: 1.6;
  color: #526072;
}

.graph-stats {
  display: flex;
  flex-wrap: wrap;
  justify-content: flex-end;
  gap: 10px;
}

.graph-toggle {
  display: inline-flex;
  align-items: center;
  gap: 10px;
  padding: 10px 14px;
  border: 1px solid rgba(191, 219, 254, 0.92);
  border-radius: 999px;
  background: rgba(255, 255, 255, 0.9);
  color: #1e3a8a;
  font-size: 12px;
  font-weight: 700;
  letter-spacing: 0.04em;
  cursor: pointer;
  transition: background-color 0.18s ease, border-color 0.18s ease, transform 0.18s ease;
}

.graph-toggle:hover {
  background: #ffffff;
  border-color: rgba(96, 165, 250, 0.92);
  transform: translateY(-1px);
}

.graph-toggle-chevron {
  display: inline-flex;
  transition: transform 0.18s ease;
}

.graph-toggle-chevron-open {
  transform: rotate(180deg);
}

.graph-stat-chip {
  display: inline-flex;
  flex-direction: column;
  align-items: flex-start;
  min-width: 74px;
  padding: 10px 12px;
  border: 1px solid rgba(191, 219, 254, 0.82);
  border-radius: 16px;
  background: rgba(255, 255, 255, 0.84);
  backdrop-filter: blur(10px);
}

.graph-stat-value {
  font-size: 18px;
  font-weight: 700;
  color: #0f172a;
}

.graph-stat-label {
  font-size: 11px;
  font-weight: 700;
  letter-spacing: 0.12em;
  text-transform: uppercase;
  color: #64748b;
}

.graph-layout {
  display: grid;
  grid-template-columns: minmax(0, 1.85fr) minmax(280px, 1fr);
  gap: 18px;
  padding: 18px 22px 22px;
}

.graph-collapsed {
  position: relative;
  z-index: 1;
  margin: 18px 22px 22px;
  padding: 18px 20px;
  border: 1px dashed rgba(147, 197, 253, 0.9);
  border-radius: 18px;
  background: rgba(255, 255, 255, 0.74);
}

.graph-collapsed-title {
  font-size: 14px;
  font-weight: 700;
  color: #0f172a;
}

.graph-collapsed-body {
  margin-top: 6px;
  font-size: 12px;
  line-height: 1.7;
  color: #526072;
}

.graph-canvas-card,
.graph-side-card {
  border: 1px solid rgba(191, 219, 254, 0.72);
  border-radius: 20px;
  background: rgba(255, 255, 255, 0.82);
  backdrop-filter: blur(10px);
  box-shadow: inset 0 1px 0 rgba(255, 255, 255, 0.94);
}

.graph-canvas-card {
  position: relative;
  display: flex;
  flex-direction: column;
  min-height: 100%;
  padding: 14px;
}

.graph-canvas-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 12px;
}

.graph-section-label {
  margin-bottom: 10px;
  font-size: 11px;
  font-weight: 700;
  letter-spacing: 0.12em;
  text-transform: uppercase;
  color: #64748b;
}

.graph-section-label-compact {
  margin-bottom: 0;
}

.graph-legend {
  display: flex;
  flex-wrap: wrap;
  justify-content: flex-end;
  gap: 8px;
}

.graph-legend-item {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  padding: 6px 10px;
  border-radius: 999px;
  font-size: 11px;
  font-weight: 700;
  line-height: 1;
}

.graph-legend-dot {
  width: 9px;
  height: 9px;
  border-radius: 999px;
  background: var(--legend-fill);
}

.graph-canvas-shell {
  position: relative;
  flex: 1;
  overflow: hidden;
  min-height: 640px;
  border: 1px solid rgba(148, 163, 184, 0.16);
  border-radius: 18px;
  background:
    radial-gradient(circle at top, rgba(249, 115, 22, 0.18), transparent 34%),
    radial-gradient(circle at bottom right, rgba(37, 99, 235, 0.14), transparent 28%),
    linear-gradient(145deg, rgba(255, 252, 245, 0.96), rgba(239, 246, 255, 0.98));
  box-shadow:
    inset 0 1px 0 rgba(255, 255, 255, 0.82),
    0 10px 30px rgba(148, 163, 184, 0.12);
}

.graph-canvas-shell::before {
  content: '';
  position: absolute;
  inset: 0;
  background-image:
    linear-gradient(rgba(148, 163, 184, 0.13) 1px, transparent 1px),
    linear-gradient(90deg, rgba(148, 163, 184, 0.13) 1px, transparent 1px);
  background-size: 22px 22px;
  pointer-events: none;
}

.graph-canvas {
  display: block;
  width: 100%;
  height: 100%;
  min-height: 640px;
}

.graph-edge-group,
.graph-node-group {
  transition: opacity 0.18s ease, transform 0.18s ease;
}

.graph-edge-line {
  stroke: #94a3b8;
  stroke-width: 2.5;
  stroke-linecap: round;
  opacity: 0.84;
  transition: opacity 0.18s ease, stroke-width 0.18s ease, stroke 0.18s ease;
}

.graph-edge-group.graph-edge-active .graph-edge-line {
  stroke: #1d4ed8;
  stroke-width: 3.4;
  opacity: 1;
}

.graph-edge-group.graph-edge-muted {
  opacity: 0.16;
}

.graph-edge-badge {
  fill: rgba(255, 255, 255, 0.92);
  stroke: rgba(148, 163, 184, 0.36);
  filter: drop-shadow(0 6px 12px rgba(148, 163, 184, 0.18));
}

.graph-edge-text {
  fill: #475569;
  font-size: 11px;
  font-weight: 700;
  letter-spacing: 0.02em;
}

.graph-node-group {
  cursor: pointer;
  outline: none;
}

.graph-node-group.graph-node-muted {
  opacity: 0.24;
}

.graph-node-group.graph-node-active {
  opacity: 1;
}

.graph-node-group.graph-node-active .graph-node-core {
  filter: drop-shadow(0 10px 18px rgba(37, 99, 235, 0.16));
}

.graph-node-group:focus-visible .graph-node-core {
  stroke-width: 4;
}

.graph-node-halo {
  transition: opacity 0.18s ease;
}

.graph-node-match-ring {
  fill: none;
  stroke-width: 2;
  stroke-dasharray: 5 4;
  opacity: 0.9;
}

.graph-node-core {
  stroke-width: 3;
}

.graph-node-inner {
  fill: rgba(255, 255, 255, 0.98);
}

.graph-node-label {
  fill: #0f172a;
  font-size: 12px;
  font-weight: 700;
  text-anchor: middle;
}

.graph-node-meta {
  fill: #64748b;
  font-size: 10px;
  font-weight: 600;
  text-anchor: middle;
}

.graph-hover-card {
  position: relative;
  width: 100%;
  max-height: none;
  margin-top: 14px;
  overflow: hidden;
  padding: 12px;
  border: 1px solid rgba(191, 219, 254, 0.78);
  border-radius: 16px;
  background: rgba(255, 255, 255, 0.96);
  box-shadow: 0 18px 30px rgba(15, 23, 42, 0.12);
  backdrop-filter: blur(12px);
}

.graph-hover-type {
  display: inline-flex;
  align-items: center;
  padding: 4px 8px;
  border-radius: 999px;
  font-size: 10px;
  font-weight: 700;
  letter-spacing: 0.08em;
  text-transform: uppercase;
}

.graph-hover-title {
  margin-top: 8px;
  font-size: 14px;
  font-weight: 700;
  color: #0f172a;
}

.graph-hover-subtitle {
  margin-top: 4px;
  font-size: 11px;
  line-height: 1.5;
  color: #64748b;
}

.graph-hover-metrics {
  display: flex;
  gap: 10px;
  margin-top: 8px;
  font-size: 11px;
  font-weight: 700;
  color: #475569;
}

.graph-hover-relations {
  display: flex;
  flex-direction: column;
  gap: 8px;
  margin-top: 10px;
}

.graph-hover-relation {
  display: flex;
  flex-direction: column;
  gap: 2px;
  padding-top: 8px;
  border-top: 1px solid rgba(226, 232, 240, 0.88);
}

.graph-hover-relation:first-child {
  padding-top: 0;
  border-top: 0;
}

.graph-hover-relation-type {
  font-size: 10px;
  font-weight: 700;
  letter-spacing: 0.08em;
  text-transform: uppercase;
  color: #64748b;
}

.graph-hover-relation-node {
  font-size: 12px;
  line-height: 1.5;
  color: #0f172a;
}

.graph-side {
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.graph-side-card {
  padding: 16px;
}

.graph-chip-row {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.graph-chip {
  display: inline-flex;
  align-items: center;
  max-width: 100%;
  padding: 7px 12px;
  border-radius: 999px;
  font-size: 12px;
  font-weight: 600;
  line-height: 1.4;
}

.graph-chip-hit {
  border-width: 1px;
  border-style: solid;
}

.graph-chip-type {
  border-width: 1px;
  border-style: solid;
}

.graph-insight-list {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.graph-insight-item {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.graph-insight-key {
  font-size: 11px;
  font-weight: 700;
  letter-spacing: 0.08em;
  text-transform: uppercase;
  color: #64748b;
}

.graph-insight-value {
  font-size: 12px;
  line-height: 1.6;
  color: #1e293b;
}

.graph-empty-tip {
  font-size: 12px;
  line-height: 1.7;
  color: #64748b;
}

.graph-paper-list {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.graph-paper-card {
  padding: 12px 14px;
  border-radius: 16px;
  background: linear-gradient(135deg, rgba(219, 234, 254, 0.78), rgba(239, 246, 255, 0.96));
  color: #1d4ed8;
  font-size: 13px;
  line-height: 1.55;
  box-shadow: inset 0 1px 0 rgba(255, 255, 255, 0.9);
}

.graph-state {
  padding: 24px 22px 22px;
  border-top: 1px solid rgba(219, 234, 254, 0.72);
}

.graph-state-kicker {
  font-size: 11px;
  font-weight: 700;
  letter-spacing: 0.12em;
  text-transform: uppercase;
  color: #64748b;
}

.graph-state-title {
  margin-top: 8px;
  font-size: 18px;
  font-weight: 700;
  color: #0f172a;
}

.graph-state-body {
  margin-top: 6px;
  max-width: 34rem;
  font-size: 13px;
  line-height: 1.7;
  color: #526072;
}

@media (max-width: 960px) {
  .graph-layout {
    grid-template-columns: minmax(0, 1fr);
  }

  .graph-hover-card {
    width: 100%;
    margin: 12px 0 0;
  }
}

@media (max-width: 640px) {
  .graph-panel-header {
    align-items: flex-start;
    flex-direction: column;
    padding: 18px 16px 0;
  }

  .graph-layout {
    grid-template-columns: minmax(0, 1fr);
    padding: 16px;
  }

  .graph-title {
    font-size: 20px;
  }

  .graph-stats {
    justify-content: flex-start;
  }

  .graph-header-actions {
    width: 100%;
    align-items: flex-start;
  }

  .graph-stat-chip {
    min-width: 68px;
    padding: 8px 10px;
  }

  .graph-collapsed {
    margin: 16px;
  }

  .graph-canvas-header {
    flex-direction: column;
    align-items: flex-start;
  }

  .graph-legend {
    justify-content: flex-start;
  }

  .graph-canvas-card,
  .graph-side-card {
    padding: 14px;
  }

  .graph-canvas-shell,
  .graph-canvas {
    min-height: 520px;
  }

  .graph-state {
    padding: 18px 16px 16px;
  }
}
</style>
