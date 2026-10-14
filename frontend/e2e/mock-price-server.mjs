// Stands in for an external price API during the end-to-end tests: fixed prices, in Rial like
// most Iranian services. Usage: node e2e/mock-price-server.mjs <port>
import { createServer } from 'node:http'

const port = Number(process.argv[2] ?? 8099)
const stock = { insCode: '46348559193224090', lVal18AFC: 'فولاد', lVal30: 'فولاد مبارکه اصفهان', flow: 1, lastDate: 1 }

createServer((request, response) => {
  if (request.url === '/health') {
    response.end('ok')
    return
  }
  if (request.url?.startsWith('/tsetmc/')) {
    response.setHeader('content-type', 'application/json')
    if (request.url.startsWith('/tsetmc/Instrument/GetInstrumentSearch/')) {
      response.end(JSON.stringify({ instrumentSearch: [stock] }))
    } else if (request.url === `/tsetmc/Instrument/GetInstrumentInfo/${stock.insCode}`) {
      response.end(JSON.stringify({ instrumentInfo: stock }))
    } else if (request.url === `/tsetmc/ClosingPrice/GetClosingPriceInfo/${stock.insCode}`) {
      const yesterday = new Date(Date.now() - 86_400_000).toISOString().slice(0, 10).replaceAll('-', '')
      response.end(JSON.stringify({ closingPriceInfo: { insCode: '0', pClosing: 35358, pDrCotVal: 35540,
        dEven: Number(yesterday), hEven: 123000, qTotTran5J: 100 } }))
    } else {
      response.statusCode = 404
      response.end('{}')
    }
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
