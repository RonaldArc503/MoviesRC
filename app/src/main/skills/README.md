Carpeta para colocar las "skills" que Codex ejecutará.

Formato sugerido (ejemplo YAML):

---
name: ejemplo_saludo
description: Saluda al usuario y devuelve un mensaje.
version: 1.0
inputs:
  - nombre: usuario
    type: string
command: "say_hello --nombre {usuario}"
---

Guarda aquí archivos `.yaml`, `.yml` o `.json` que describan cada skill y cómo ejecutarla.
