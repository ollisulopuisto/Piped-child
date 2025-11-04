// src/utils/auth.js

// This will be a placeholder for a real authentication service.
// For now, it will return a mocked user object.
let currentUser = {
  username: "child",
  role: "child", // or 'parent'
};

export function getAuthenticatedUser() {
  return currentUser;
}

export function isParent() {
  return getAuthenticatedUser().role === "parent";
}

// In a real application, you would have login/logout functions
// that fetch a user from the backend and set the currentUser.
// For this example, we'll add a function to toggle the user for testing.

export function __test__toggleUserRole() {
  if (currentUser.role === "child") {
    currentUser = { username: "parent", role: "parent" };
  } else {
    currentUser = { username: "child", role: "child" };
  }
  return currentUser;
}
