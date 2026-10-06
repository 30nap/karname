import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { useState } from 'react'
import { IRT } from '@/lib/format/money'
import { authStatus, makeMe } from '@/test/fixtures'
import { renderWithProviders, stubApi } from '@/test/utils'
import { MoneyInput } from './MoneyInput'

function Harness({ onValue, commodity = IRT }: { onValue: (v: string) => void; commodity?: typeof IRT }) {
  const [value, setValue] = useState('')
  return (
    <MoneyInput
      aria-label="مبلغ"
      value={value}
      onChange={(v) => { setValue(v); onValue(v) }}
      commodity={commodity}
      showQuickButtons
    />
  )
}

describe('MoneyInput', () => {
  afterEach(() => vi.unstubAllGlobals())

  it('shows live Persian separators and the amount in words', async () => {
    stubApi((url) => (url === '/auth/status' ? { body: authStatus() } : undefined))
    const onValue = vi.fn()
    const user = userEvent.setup()
    renderWithProviders(<Harness onValue={onValue} />)
    const input = await screen.findByLabelText('مبلغ')
    await user.type(input, '1234567')
    expect(input).toHaveValue('۱٬۲۳۴٬۵۶۷')
    expect(onValue).toHaveBeenLastCalledWith('1234567')
    expect(screen.getByText('یک میلیون و دویست و سی و چهار هزار و پانصد و شصت و هفت تومان')).toBeInTheDocument()
  })

  it('accepts Persian digits and multiplies with the quick buttons', async () => {
    stubApi((url) => (url === '/auth/status' ? { body: authStatus() } : undefined))
    const onValue = vi.fn()
    const user = userEvent.setup()
    renderWithProviders(<Harness onValue={onValue} />)
    const input = await screen.findByLabelText('مبلغ')
    await user.type(input, '۲۵۰')
    expect(onValue).toHaveBeenLastCalledWith('250')
    await user.click(screen.getByRole('button', { name: 'هزار' }))
    expect(input).toHaveValue('۲۵۰٬۰۰۰')
    expect(onValue).toHaveBeenLastCalledWith('250000')
    expect(screen.getByText('دویست و پنجاه هزار تومان')).toBeInTheDocument()
  })

  it('ignores characters that cannot be part of a number', async () => {
    stubApi((url) => (url === '/auth/status' ? { body: authStatus() } : undefined))
    const onValue = vi.fn()
    const user = userEvent.setup()
    renderWithProviders(<Harness onValue={onValue} />)
    const input = await screen.findByLabelText('مبلغ')
    await user.type(input, '12x3')
    expect(input).toHaveValue('۱۲۳')
    expect(onValue).toHaveBeenLastCalledWith('123')
  })

  it('uses Latin digits and the Rial unit when the user prefers them', async () => {
    stubApi((url) => (url === '/auth/status' ? { body: authStatus(makeMe({ digitStyle: 'LATIN', displayUnit: 'RIAL' })) } : undefined))
    const user = userEvent.setup()
    renderWithProviders(<Harness onValue={() => {}} />)
    const input = await screen.findByLabelText('مبلغ')
    await waitFor(() => expect(screen.getByText('ریال')).toBeInTheDocument())
    await user.type(input, '1500')
    expect(input).toHaveValue('1,500')
  })
})
