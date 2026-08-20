import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { App } from './App'
import './index.css'

const container = document.getElementById('root')

// Fail closed, in the browser too. A missing mount point means index.html and this entry point
// disagree, which is a build or deployment fault — rendering nothing and logging nothing would
// leave a blank page whose cause is invisible from the outside.
if (!container) {
  throw new Error('#root is missing from index.html; the application cannot mount.')
}

createRoot(container).render(
  <StrictMode>
    <App />
  </StrictMode>,
)
