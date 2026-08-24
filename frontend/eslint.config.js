import reactHooks from 'eslint-plugin-react-hooks'
import reactRefresh from 'eslint-plugin-react-refresh'
import tseslint from 'typescript-eslint'

/**
 * ESLint 10 flat configuration.
 *
 * `yarn lint` runs with `--max-warnings 0`, so there is no difference here between `warn` and
 * `error` in effect. Rules are declared as `error` anyway, because a level that means something
 * other than what it says is a level the next reader has to check the script to interpret.
 */
export default tseslint.config(
  { ignores: ['dist/**', 'coverage/**', 'src/api/generated/**'] },

  ...tseslint.configs.recommended,
  reactHooks.configs.flat['recommended-latest'],

  {
    files: ['**/*.{ts,tsx}'],
    plugins: { 'react-refresh': reactRefresh },
    rules: {
      'react-refresh/only-export-components': ['error', { allowConstantExport: true }],

      // Invariant F1, as a lint rule rather than a review finding. A component that calls fetch
      // directly bypasses error mapping, token refresh and the generated type contract at once —
      // and that is precisely the kind of shortcut that looks harmless in the diff that adds it.
      'no-restricted-globals': [
        'error',
        {
          name: 'fetch',
          message:
            'Invariant F1: components never call fetch directly. All I/O goes through src/api/, ' +
            'whose types are generated from the OpenAPI document.',
        },
      ],
    },
  },

  {
    // src/api is the one place that is allowed to reach the network; that is what makes it the
    // single choke point for error mapping and auth refresh.
    files: ['src/api/**/*.{ts,tsx}'],
    rules: { 'no-restricted-globals': 'off' },
  },
)
