# Contributing to oci-log4j2

Thank you for your interest in contributing to **oci-log4j2**!  
Contributions of all kinds are welcome — bug reports, feature requests, documentation improvements, and pull requests.

This document explains how to contribute effectively and consistently.

---

## 🧱 How to Contribute

### 1. Fork the repository
Create your own fork of the project on GitHub:

https://github.com/luke71/oci-log4j2

Then clone your fork locally:

git clone https://github.com/<your-username>/oci-log4j2

---

### 2. Create a feature branch

git checkout -b feature/my-new-feature

Use a descriptive branch name (e.g., `fix/batch-flush-bug`, `feature/json-layout-support`).

---

### 3. Make your changes
Please follow these guidelines:

- Keep the code clean and readable  
- Follow Java best practices  
- Add comments where needed  
- Include tests for new features or bug fixes  
- Ensure the project builds successfully with Maven  

---

### 4. Run the test suite

Before submitting your changes, run:

mvn clean test


All tests must pass.  
If you add new functionality, please include new tests.

---

### 5. Commit your changes

Use clear and meaningful commit messages:

git commit -m "Add support for ..."

---

### 6. Push and open a Pull Request

git push origin feature/my-new-feature

Then open a Pull Request (PR) on GitHub.

Your PR should include:

- A clear description of the change  
- The motivation behind it  
- Any relevant issue numbers  
- Notes on testing or potential impacts  

---

## 🧪 Coding Style

- Java 17  
- Follow standard Log4j2 plugin conventions  
- Use meaningful variable and method names  
- Keep methods small and focused  
- Avoid unnecessary dependencies  

---

## 📝 Reporting Issues

If you find a bug or want to request a feature, please open an issue:

https://github.com/luke71/oci-log4j2/issues


Include:

- Steps to reproduce  
- Expected behavior  
- Actual behavior  
- Logs or stack traces if relevant  

---

## 📄 License

By contributing to this project, you agree that your contributions will be licensed under the **Apache License 2.0**, the same license that covers this repository.

---

## 🙌 Thank You

Your contributions help make this project better for everyone.  
Thank you for taking the time to improve **oci-log4j2**!
