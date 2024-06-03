/** @type {import('tailwindcss').Config} */
const defaultTheme = require('tailwindcss/defaultTheme')

export default {
  content: ['./src/**/*.{mjs,js,ts,jsx,tsx}'],
  theme: {
    screens: {
      'mobile': '400px',
      'narrow': '600px',
      'laptop': '1100px',
    },
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
        swivel: 'swivel 0.3s linear'
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
      primary4: "#006FE3",
      primary5: "#2D94FF",
      blue5: "#426d9e",
      olhcGreen: "#39CF63",
      olhcRed: "#FF5A50",
      orangeShadow: "#F7931ABF",

      // status colors
      statusOrange: "#FFA337",
      statusYellow: "#FFEB82",
      statusRed: "#FF7169",
      statusBlue: "#2D94FF",
      statusGreen: "#42C66B",

      // legacy
      lightBackground: "#B5CBCD",
      darkBackground: "#66B0B6",
      neutralGray: "#8C8C8C",
      mutedGray: "#555555",
      darkGray: "#3F3F3F",
      white: "#FFF",
      green: "#10A327",
      brightGreen: "#1cd23a",
      red: "#7F1D1D",
      brightRed: "#ff7169",
      black: "#111",
    }
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
