import myAxios from '@/request';

export type RagJobStatus = 'QUEUED' | 'RUNNING' | 'DUPLICATE_SKIPPED' | 'COMPLETED' | 'FAILED';
export type RagBatchStatus = 'QUEUED' | 'RUNNING' | 'COMPLETED' | 'PARTIAL_FAILED' | 'FAILED';
export type RagIngestionStage =
  | 'UPLOAD'
  | 'HEADER_EXTRACTION'
  | 'DEDUPLICATION'
  | 'FULLTEXT_EXTRACTION'
  | 'TEI_PARSING'
  | 'JSONL_WRITING'
  | 'EMBEDDING'
  | 'PERSISTING'
  | 'COMPLETED'
  | 'FAILED';

export interface RagUploadAcceptedResponse {
  jobId: string;
  documentId: string;
  status: RagJobStatus;
  stage: RagIngestionStage;
}

export interface RagBatchAcceptedResponse {
  batchId: string;
  status: RagBatchStatus;
  totalFiles: number;
}

export interface RagDocumentRecord {
  documentId: string;
  duplicateOfDocumentId?: string | null;
  latestJobId?: string | null;
  doiRaw?: string | null;
  doiNormalized?: string | null;
  title?: string | null;
  authors?: string[];
  journal?: string | null;
  publicationDate?: string | null;
  publicationYear?: number | null;
  sourceFilename?: string | null;
  status: string;
  createdAt?: string;
  updatedAt?: string;
}

export interface RagIngestionBatchRecord {
  batchId: string;
  sourceFolder: string;
  status: RagBatchStatus;
  totalFiles?: number | null;
  processedFiles?: number | null;
  completedFiles?: number | null;
  duplicateFiles?: number | null;
  failedFiles?: number | null;
  chunkCount?: number | null;
  totalElapsedMs?: number | null;
  startedAt?: string | null;
  finishedAt?: string | null;
  createdAt?: string;
  updatedAt?: string;
}

export const paperImportService = {
  async uploadDocument(file: File): Promise<RagUploadAcceptedResponse> {
    const formData = new FormData();
    formData.append('file', file);
    const { data } = await myAxios.post('/rag/documents', formData, {
      headers: { 'Content-Type': 'multipart/form-data' },
      timeout: 120000,
    });
    return data as RagUploadAcceptedResponse;
  },

  async getDocument(documentId: string): Promise<RagDocumentRecord> {
    const { data } = await myAxios.get(`/rag/documents/${documentId}`);
    return data as RagDocumentRecord;
  },

  async ingestFolder(folderPath: string): Promise<RagBatchAcceptedResponse> {
    const { data } = await myAxios.post('/rag/batches/folder', { folderPath }, { timeout: 120000 });
    return data as RagBatchAcceptedResponse;
  },

  async getBatch(batchId: string): Promise<RagIngestionBatchRecord> {
    const { data } = await myAxios.get(`/rag/batches/${batchId}`);
    return data as RagIngestionBatchRecord;
  },
};
