import request from '@/request';
import type { QuestionGraphView } from '@/types/kg';

export const kgService = {
  async queryQuestionGraph(prompt: string): Promise<QuestionGraphView> {
    const response = await request.get<QuestionGraphView>('/kg/query', {
      params: { prompt },
    });
    return response.data;
  },
};
