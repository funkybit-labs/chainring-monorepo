/** @type {import('tailwindcss').Config} */
const defaultTheme = require('tailwindcss/defaultTheme')

export default {
  content: ['./src/**/*.{mjs,js,ts,jsx,tsx}'],
  theme: {
    extend: {
      fontFamily: {
        sans: ['Inter', ...defaultTheme.fontFamily.sans]
      },
      keyframes: {
        swivel: {
          'from': {transform: 'rotateY(0deg)'},
          'to': {transform: 'rotateY(180deg)'}
        }
      },
      animation: {
        swivel: 'swivel 0.5s linear'
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
