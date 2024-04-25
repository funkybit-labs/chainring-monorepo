/** @type {import('tailwindcss').Config} */
const defaultTheme = require('tailwindcss/defaultTheme')

export default {
  content: ['./src/**/*.{mjs,js,ts,jsx,tsx}'],
  theme: {
    screens: {
      'sm': '750px',
      'md': '1000px',
      'lg': '1500px',
      'xl': '1750px',
      '2xl': '2250px',
    },
    extend: {
      fontFamily: {
        sans: ['"Roboto Mono"', ...defaultTheme.fontFamily.sans]
      }
    },
    colors: {
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
      pattern: /grid-cols-/,
      variants: ['sm', 'md', 'lg', 'xl', '2xl']
    },
    {
      pattern: /col-span-/,
    }
  ]
}
