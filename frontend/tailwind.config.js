/** @type {import('tailwindcss').Config} */
export default {
  content: ['./index.html', './src/**/*.{ts,tsx}'],
  theme: {
    extend: {
      // Mobile-first is a constraint, not a preference: unprefixed classes ARE the phone
      // case, and `sm:` and up are additions. Every view is built at 375 px and widened.
      screens: {
        xs: '375px',
      },
    },
  },
  plugins: [],
}
