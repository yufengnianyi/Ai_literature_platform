export interface QuestionGraphNode {
  id: string;
  label: string;
  entityType: string;
  matched: boolean;
  degree: number;
  papers: string[];
}

export interface QuestionGraphEdge {
  id: string;
  source: string;
  target: string;
  relationType: string;
}

export interface QuestionGraphView {
  prompt: string;
  status: 'READY' | 'EMPTY' | 'UNAVAILABLE';
  matchedEntities: string[];
  nodes: QuestionGraphNode[];
  edges: QuestionGraphEdge[];
  papers: string[];
}
