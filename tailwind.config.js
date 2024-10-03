module.exports = {
  content: ["./src/**/*", "./resources/public/index.html"],
  // darkMode: 'selector',
  theme: {
    extend: {},
  },
  variants: {
    extend: {
      visibility: ['group-hover'],
    },
  },
  // plugins: [require('@tailwindcss/typography')],
};
