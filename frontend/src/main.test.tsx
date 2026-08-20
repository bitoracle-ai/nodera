const renderInto = vi.fn()
const createRoot = vi.fn(() => ({ render: renderInto, unmount: vi.fn() }))

vi.mock('react-dom/client', () => ({ createRoot }))

/**
 * The entry point has exactly one branch, and it is the interesting one: what happens when the
 * mount point is missing. Left untested, a refactor that turns the throw into a silent return
 * produces a blank page whose cause is invisible from the outside — the failure mode the throw
 * exists to prevent.
 */
describe('main', () => {
  beforeEach(() => {
    vi.resetModules()
    vi.clearAllMocks()
    document.body.replaceChildren()
  })

  it('mounts the application into #root', async () => {
    const container = document.createElement('div')
    container.id = 'root'
    document.body.append(container)

    await import('./main')

    expect(createRoot).toHaveBeenCalledWith(container)
    expect(renderInto).toHaveBeenCalledOnce()
  })

  it('refuses to start when #root is missing, rather than rendering nothing quietly', async () => {
    await expect(import('./main')).rejects.toThrow('#root is missing')

    expect(createRoot).not.toHaveBeenCalled()
  })
})
