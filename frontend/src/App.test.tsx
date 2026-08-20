import { render, screen } from '@testing-library/react'
import { App } from './App'

describe('App', () => {
  it('names the product in a single top-level heading', () => {
    render(<App />)

    expect(screen.getByRole('heading', { level: 1 })).toHaveTextContent('Nodera')
  })

  it('says which work package it is a placeholder for, so nobody mistakes it for the shell', () => {
    render(<App />)

    expect(screen.getByText(/OPS-01/)).toBeInTheDocument()
    expect(screen.getByText(/WEB-01/)).toBeInTheDocument()
  })
})
