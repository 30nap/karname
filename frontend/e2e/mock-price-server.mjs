// Stands in for an external price API during the end-to-end tests: fixed prices, in Rial like
// most Iranian services. Usage: node e2e/mock-price-server.mjs <port>
import { createServer } from 'node:http'

const port = Number(process.argv[2] ?? 8099)

createServer((request, response) => {
  if (request.url === '/health') {
    response.end('ok')
    return
  }
  if (request.url === '/prices.json') {
    response.setHeader('content-type', 'application/json')
    response.end(JSON.stringify({ data: { usd: { price: '1025000' }, gold18: { price: '89500000' } } }))
    return
  }
  if (request.url === '/prices-toman.json') {
    response.setHeader('content-type', 'application/json')
    response.end(JSON.stringify({ usd: 103000 }))
    return
  }
  // a service refusing the request, the way Nobitex answers an unknown symbol
  if (request.url === '/rejects.json') {
    response.statusCode = 400
    response.setHeader('content-type', 'application/json')
    response.end(JSON.stringify({ status: 'failed', code: 'InvalidSrcCurrency', message: 'Invalid srcCurrency' }))
    return
  }
  response.statusCode = 404
  response.end()
}).listen(port, '127.0.0.1')
