import { generateService } from '@umijs/openapi';

generateService({
  schemaPath: 'http://127.0.0.1:8081/api/v3/api-docs',
  serversPath: './src',
  requestLibPath: "import request from '@/request'",
});
