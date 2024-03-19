/** @type {import('tailwindcss').Config} */

export default {
  content: ['./src/**/*.{mjs,js,ts,jsx,tsx}'],
  theme: {
    extend: {},
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
      brightRed: "#a12222"
    }
  },
  plugins: [
    require('@tailwindcss/forms')
  ]
}
