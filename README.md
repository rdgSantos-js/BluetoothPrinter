# PrinterBT

**PrinterBT** é uma livraria Kotlin que funciona como uma **ponte entre dispositivos Android e impressoras Bluetooth**.  
Ele foi desenvolvido para ser integrado com aplicações **GeneXus**.

---

## 🔹 Objetivo

O principal objetivo do **PrinterBT** é expor uma interface nativa de Android que facilite o uso de impressoras via Bluetooth por aplicações construídas em GeneXus.

---

## 📚 Base do Projeto

Este projeto foi desenvolvido tomando como referência o repositório oficial de exemplos de extensões para GeneXus:  
👉 [SDExtensionsSample (GeneXusLabs)](https://github.com/genexuslabs/SDExtensionsSample)

---

## 📌 Observação

O **PrinterBT** gerencia **permissões, descoberta de dispositivos e comunicação básica com as impressoras**.

Para passar o repositório para a pasta do maven, executar o comando "gradlew publishDebugPublicationToInternalRepository"
