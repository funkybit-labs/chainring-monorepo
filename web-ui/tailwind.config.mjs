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
        sans: ['"Roboto Mono"', ...defaultTheme.fontFamily.sans]
      }
    },
    colors: {
      darkBluishGray10: "#1A1B21",
      darkBluishGray9: "#1B222B",
      darkBluishGray8: "#1F2A39",
      darkBluishGray7: "#313D4E",
      darkBluishGray6: "#444F5F",
      darkBluishGray4: "#6F7885",
      darkBluishGray3: "#89919D",
      darkBluishGray2: "#A0A6B1",
      darkBluishGray1: "#BABEC5",
      lightBluishGray5: "#CDD1D7",
      primary4: "#FF8718",
      primary5: "#E4720B",
      olhcGreen: "#39CF63",
      olhcRed: "#FF5A50",

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
      brightRed: "#a12222",
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
