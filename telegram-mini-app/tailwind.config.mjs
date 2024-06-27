/** @type {import('tailwindcss').Config} */
const defaultTheme = require('tailwindcss/defaultTheme')

export default {
  content: ['./src/**/*.{mjs,js,ts,jsx,tsx}'],
  theme: {
    extend: {
      fontFamily: {
        sans: ['Inter', ...defaultTheme.fontFamily.sans]
      },
      fontSize: {
        'xxs': '0.625rem',  // 10px
        'xxxs': '0.5rem',   // 8px
      },
      keyframes: {
        swivel: {
          'from': {transform: 'rotateY(0deg)'},
          'to': {transform: 'rotateY(180deg)'}
        },
        gameOrangeBlink: {
          '0%, 100%': { backgroundColor: 'transparent' },
          '50%': { backgroundColor: '#FFA337' },
        },
      },
      animation: {
        swivel: 'swivel 0.5s linear',
        gameOrangeBlink: 'gameOrangeBlink 1.5s steps(1, end) infinite',
      }
    },
    colors: {
      darkBluishGray10: "#1F2A39",
      darkBluishGray9: "#253447",
      darkBluishGray8: "#2A3F59",
      darkBluishGray7: "#314965",
      darkBluishGray6: "#34557C",
      darkBluishGray5: "#406087",
      darkBluishGray4: "#54749A",
      darkBluishGray3: "#6382A8",
      darkBluishGray2: "#7F9CBF",
      darkBluishGray1: "#95B2D4",
      lightBluishGray2: "#EBEEF4",
      lightBluishGray5: "#CDD1D7",
      primary4: "#FFA337",
      primary5: "#FF8A00",
      blue5: "#426d9e",
      white: "#FFF",
      black: "#111",
      transparent: "transparent",

      // reaction game colors
      gameOrange: "#FFA337",
      gameBlue: "#2288E7",
      gameBlack: "#1E1E1E",
      gameRed: "#FF7169",
      gameDisabled: "#646161",
    },
  },
  plugins: [
    require('@tailwindcss/forms')
  ],
  safelist: [
    {
      pattern: /text-status/,
    }
  ]
}
