// SPDX-FileCopyrightText: Copyright 2026 Eden Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

// SPDX-FileCopyrightText: Copyright 2025 lsfg-vk
// SPDX-License-Identifier: GPL-3.0-or-later

#include "Cafe/HW/Latte/Renderer/Vulkan/FrameGen/LosslessDll.h"
#include "config/ActiveSettings.h"

#include <fstream>
#include <optional>
#include <span>
#include <tuple>

namespace LosslessDll
{
	namespace
	{
		constexpr uint16 DOS_MAGIC = 0x5A4D;
		constexpr uint32 PE_SIGNATURE = 0x00004550;
		constexpr uint16 PE32_MAGIC = 0x010B;
		constexpr uint16 PE32_PLUS_MAGIC = 0x020B;

		constexpr size_t DOS_LFANEW_OFFSET = 0x3C;
		constexpr size_t COFF_HEADER_SIZE = 20;
		constexpr size_t OPTIONAL_HEADER_SIZE_OFFSET = 16;
		constexpr size_t SECTION_HEADER_SIZE = 40;
		constexpr size_t DATA_DIRECTORY_ENTRY_SIZE = 8;
		constexpr size_t DATA_DIRECTORY_OFFSET_PE32 = 96;
		constexpr size_t DATA_DIRECTORY_OFFSET_PE32_PLUS = 112;
		constexpr size_t RESOURCE_DATA_DIRECTORY_INDEX = 2;

		constexpr size_t RESOURCE_DIRECTORY_SIZE = 16;
		constexpr size_t RESOURCE_NAMED_COUNT_OFFSET = 12;
		constexpr size_t RESOURCE_ID_COUNT_OFFSET = 14;
		constexpr size_t RESOURCE_ENTRY_SIZE = 8;
		constexpr uint32 RESOURCE_SUBDIRECTORY_FLAG = 0x80000000;
		constexpr uint32 RESOURCE_TYPE_RCDATA = 10;

		constexpr uint32 PERFORMANCE_SHADER_ID_FIRST = 280;
		constexpr uint32 PERFORMANCE_SHADER_ID_LAST = 302;

		constexpr uint32 SPIRV_MAGIC = 0x07230203;
		constexpr uint32 SPIRV_WORD_COUNT_SHIFT = 16;
		constexpr uint32 SPIRV_OPCODE_MASK = 0xffff;
		constexpr uint32 SPIRV_OP_FUNCTION = 54;
		constexpr uint32 SPIRV_OP_DECORATE = 71;
		constexpr uint32 SPIRV_DECORATION_BINDING = 33;
		constexpr uint32 SPIRV_DECORATION_DESCRIPTOR_SET = 34;
		constexpr uint32 DECORATION_LITERAL_WORD = 3;
		constexpr size_t SPIRV_HEADER_WORDS = 5;

		std::atomic<uint32> s_changeCounter{0};

		struct Section
		{
			uint32 virtualAddress;
			uint32 virtualSize;
			uint32 rawAddress;
			uint32 rawSize;
		};

		struct ResourceEntry
		{
			uint32 id;
			uint32 offset;
			bool isDirectory;
			bool isNamed;
		};

		class ImageReader
		{
		public:
			explicit ImageReader(std::span<const uint8> image) : m_image(image) {}

			template<typename T>
			bool Read(size_t offset, T& valueOut) const
			{
				if (offset > m_image.size() || m_image.size() - offset < sizeof(T))
					return false;
				std::memcpy(&valueOut, m_image.data() + offset, sizeof(T));
				return true;
			}

			bool Slice(size_t offset, size_t size, std::span<const uint8>& sliceOut) const
			{
				if (offset > m_image.size() || m_image.size() - offset < size)
					return false;
				sliceOut = m_image.subspan(offset, size);
				return true;
			}

		private:
			std::span<const uint8> m_image;
		};

		using ResourceSpans = std::map<uint32, std::span<const uint8>>;

		std::optional<size_t> FindPeHeader(const ImageReader& reader)
		{
			uint16 dosMagic{};
			if (!reader.Read(0, dosMagic) || dosMagic != DOS_MAGIC)
				return std::nullopt;
			uint32 peOffset{};
			if (!reader.Read(DOS_LFANEW_OFFSET, peOffset))
				return std::nullopt;
			uint32 peSignature{};
			if (!reader.Read(peOffset, peSignature) || peSignature != PE_SIGNATURE)
				return std::nullopt;
			return static_cast<size_t>(peOffset);
		}

		std::optional<size_t> FindDataDirectory(const ImageReader& reader, size_t optionalHeaderOffset)
		{
			uint16 optionalMagic{};
			if (!reader.Read(optionalHeaderOffset, optionalMagic))
				return std::nullopt;
			switch (optionalMagic)
			{
			case PE32_MAGIC:
				return optionalHeaderOffset + DATA_DIRECTORY_OFFSET_PE32;
			case PE32_PLUS_MAGIC:
				return optionalHeaderOffset + DATA_DIRECTORY_OFFSET_PE32_PLUS;
			default:
				return std::nullopt;
			}
		}

		bool ReadSections(const ImageReader& reader, size_t peOffset, std::vector<Section>& sectionsOut)
		{
			uint16 sectionCount{};
			uint16 optionalHeaderSize{};
			if (!reader.Read(peOffset + 4 + 2, sectionCount) || !reader.Read(peOffset + 4 + OPTIONAL_HEADER_SIZE_OFFSET, optionalHeaderSize))
				return false;

			const size_t tableOffset = peOffset + 4 + COFF_HEADER_SIZE + optionalHeaderSize;
			sectionsOut.reserve(sectionCount);
			for (size_t i = 0; i < sectionCount; ++i)
			{
				const size_t offset = tableOffset + i * SECTION_HEADER_SIZE;
				Section section{};
				if (!reader.Read(offset + 8, section.virtualSize) || !reader.Read(offset + 12, section.virtualAddress) ||
					!reader.Read(offset + 16, section.rawSize) || !reader.Read(offset + 20, section.rawAddress))
					return false;
				sectionsOut.push_back(section);
			}
			return true;
		}

		std::optional<size_t> RvaToFileOffset(std::span<const Section> sections, uint32 rva)
		{
			for (const Section& section : sections)
			{
				const uint32 span = std::max(section.virtualSize, section.rawSize);
				if (span == 0 || rva < section.virtualAddress)
					continue;
				const uint32 relative = rva - section.virtualAddress;
				if (relative < span)
					return static_cast<size_t>(section.rawAddress) + relative;
			}
			return std::nullopt;
		}

		bool ReadResourceEntries(const ImageReader& reader, size_t directoryOffset, std::vector<ResourceEntry>& entriesOut)
		{
			uint16 namedCount{};
			uint16 idCount{};
			if (!reader.Read(directoryOffset + RESOURCE_NAMED_COUNT_OFFSET, namedCount) || !reader.Read(directoryOffset + RESOURCE_ID_COUNT_OFFSET, idCount))
				return false;

			const size_t total = size_t{namedCount} + size_t{idCount};
			entriesOut.clear();
			entriesOut.reserve(total);
			for (size_t i = 0; i < total; ++i)
			{
				const size_t offset = directoryOffset + RESOURCE_DIRECTORY_SIZE + i * RESOURCE_ENTRY_SIZE;
				uint32 name{};
				uint32 data{};
				if (!reader.Read(offset, name) || !reader.Read(offset + 4, data))
					return false;
				entriesOut.push_back(ResourceEntry{
					.id = name & ~RESOURCE_SUBDIRECTORY_FLAG,
					.offset = data & ~RESOURCE_SUBDIRECTORY_FLAG,
					.isDirectory = (data & RESOURCE_SUBDIRECTORY_FLAG) != 0,
					.isNamed = (name & RESOURCE_SUBDIRECTORY_FLAG) != 0,
				});
			}
			return true;
		}

		bool ReadResourceLeaf(const ImageReader& reader, std::span<const Section> sections, size_t leafOffset, std::span<const uint8>& dataOut)
		{
			uint32 dataRva{};
			uint32 dataSize{};
			if (!reader.Read(leafOffset, dataRva) || !reader.Read(leafOffset + 4, dataSize) || dataSize == 0)
				return false;
			const std::optional<size_t> dataOffset = RvaToFileOffset(sections, dataRva);
			if (!dataOffset)
				return false;
			return reader.Slice(*dataOffset, dataSize, dataOut);
		}

		bool CollectRcData(const ImageReader& reader, std::span<const Section> sections, size_t resourceBase, ResourceSpans& resourcesOut)
		{
			std::vector<ResourceEntry> typeEntries;
			if (!ReadResourceEntries(reader, resourceBase, typeEntries))
				return false;

			for (const ResourceEntry& typeEntry : typeEntries)
			{
				if (typeEntry.isNamed || typeEntry.id != RESOURCE_TYPE_RCDATA || !typeEntry.isDirectory)
					continue;

				std::vector<ResourceEntry> nameEntries;
				if (!ReadResourceEntries(reader, resourceBase + typeEntry.offset, nameEntries))
					return false;

				for (const ResourceEntry& nameEntry : nameEntries)
				{
					if (nameEntry.isNamed || !nameEntry.isDirectory)
						continue;

					std::vector<ResourceEntry> languageEntries;
					if (!ReadResourceEntries(reader, resourceBase + nameEntry.offset, languageEntries))
						return false;

					for (const ResourceEntry& languageEntry : languageEntries)
					{
						if (languageEntry.isDirectory)
							continue;
						std::span<const uint8> data;
						if (!ReadResourceLeaf(reader, sections, resourceBase + languageEntry.offset, data))
							continue;
						resourcesOut.insert_or_assign(nameEntry.id, data);
						break;
					}
				}
			}
			return true;
		}

		std::vector<uint32> PerformanceShaderIds()
		{
			std::vector<uint32> ids{PerformanceShader::MIPMAPS, PerformanceShader::GENERATE};
			for (uint32 id = PERFORMANCE_SHADER_ID_FIRST; id <= PERFORMANCE_SHADER_ID_LAST; ++id)
				ids.push_back(id);
			return ids;
		}

		bool IsSpirvModule(std::span<const uint8> blob)
		{
			if (blob.size() < SPIRV_HEADER_WORDS * sizeof(uint32) || blob.size() % sizeof(uint32) != 0)
				return false;
			uint32 magic{};
			std::memcpy(&magic, blob.data(), sizeof(magic));
			return magic == SPIRV_MAGIC;
		}

		// the shaders declare their bindings sparsely, renumber them so they match the order in which our
		// descriptor set layouts declare them
		void RenumberBindingsInOrder(std::vector<uint32>& words)
		{
			struct Slot
			{
				uint32 set;
				uint32 binding;
				size_t literalOffset;
			};

			std::map<uint32, uint32> sets;
			std::vector<Slot> slots;

			size_t offset = SPIRV_HEADER_WORDS;
			while (offset + 1 <= words.size())
			{
				const uint32 length = words[offset] >> SPIRV_WORD_COUNT_SHIFT;
				const uint32 opcode = words[offset] & SPIRV_OPCODE_MASK;
				if (length == 0 || offset + length > words.size())
					return;
				if (opcode == SPIRV_OP_FUNCTION)
					break;
				if (opcode == SPIRV_OP_DECORATE && length >= 4)
				{
					if (words[offset + 2] == SPIRV_DECORATION_DESCRIPTOR_SET)
						sets[words[offset + 1]] = words[offset + 3];
					else if (words[offset + 2] == SPIRV_DECORATION_BINDING)
						slots.push_back(Slot{0, words[offset + 3], offset + DECORATION_LITERAL_WORD});
				}
				offset += length;
			}

			for (Slot& slot : slots)
			{
				const auto hit = sets.find(words[slot.literalOffset - 2]);
				slot.set = hit == sets.end() ? 0 : hit->second;
			}

			std::stable_sort(slots.begin(), slots.end(), [](const Slot& lhs, const Slot& rhs) {
				return std::tie(lhs.set, lhs.binding) < std::tie(rhs.set, rhs.binding);
			});

			for (size_t i = 0; i < slots.size(); ++i)
				words[slots[i].literalOffset] = static_cast<uint32>(i);
		}

		std::vector<uint32> AdoptSpirvModule(std::span<const uint8> blob)
		{
			if (!IsSpirvModule(blob))
				return {};
			std::vector<uint32> words(blob.size() / sizeof(uint32));
			std::memcpy(words.data(), blob.data(), blob.size());
			RenumberBindingsInOrder(words);
			return words;
		}

		bool HasNativeShaders(const ResourceSpans& resources)
		{
			for (uint32 id : PerformanceShaderIds())
			{
				const auto hit = resources.find(id + PerformanceShader::NATIVE_FP16_OFFSET);
				if (hit == resources.end() || !IsSpirvModule(hit->second))
					return false;
			}
			return true;
		}

		Status ReadImageFile(const fs::path& path, std::vector<uint8>& imageOut)
		{
			std::error_code ec;
			if (!fs::exists(path, ec))
				return Status::NotInstalled;

			std::ifstream file(path, std::ios::binary | std::ios::ate);
			if (!file.is_open())
				return Status::UnreadableFile;

			const std::streamsize size = file.tellg();
			if (size <= 0)
				return Status::UnreadableFile;
			imageOut.resize(static_cast<size_t>(size));
			file.seekg(0, std::ios::beg);
			if (!file.read(reinterpret_cast<char*>(imageOut.data()), size))
				return Status::UnreadableFile;
			return Status::Ok;
		}

		Status ParseShaderSpans(std::span<const uint8> image, ResourceSpans& resourcesOut)
		{
			const ImageReader reader{image};
			const std::optional<size_t> peOffset = FindPeHeader(reader);
			if (!peOffset)
				return Status::NotPortableExecutable;

			const std::optional<size_t> dataDirectory = FindDataDirectory(reader, *peOffset + 4 + COFF_HEADER_SIZE);
			if (!dataDirectory)
				return Status::NotPortableExecutable;

			std::vector<Section> sections;
			if (!ReadSections(reader, *peOffset, sections))
				return Status::NotPortableExecutable;

			uint32 resourceRva{};
			if (!reader.Read(*dataDirectory + RESOURCE_DATA_DIRECTORY_INDEX * DATA_DIRECTORY_ENTRY_SIZE, resourceRva) || resourceRva == 0)
				return Status::MissingShaders;

			const std::optional<size_t> resourceBase = RvaToFileOffset(sections, resourceRva);
			if (!resourceBase)
				return Status::NotPortableExecutable;

			resourcesOut.clear();
			if (!CollectRcData(reader, sections, *resourceBase, resourcesOut))
				return Status::MissingShaders;
			if (!HasNativeShaders(resourcesOut))
				return Status::MissingShaders;
			return Status::Ok;
		}
	}

	fs::path GetInstalledPath()
	{
		return ActiveSettings::GetUserDataPath("lossless/Lossless.dll");
	}

	Status Validate(const fs::path& path)
	{
		std::vector<uint8> image;
		const Status readStatus = ReadImageFile(path, image);
		if (readStatus != Status::Ok)
			return readStatus;
		ResourceSpans spans;
		return ParseShaderSpans(image, spans);
	}

	Status GetInstalledStatus()
	{
		return Validate(GetInstalledPath());
	}

	Status LoadShaderModules(ShaderModules& modulesOut)
	{
		std::vector<uint8> image;
		const Status readStatus = ReadImageFile(GetInstalledPath(), image);
		if (readStatus != Status::Ok)
			return readStatus;

		ResourceSpans spans;
		const Status parseStatus = ParseShaderSpans(image, spans);
		if (parseStatus != Status::Ok)
			return parseStatus;

		modulesOut.clear();
		for (const uint32 id : PerformanceShaderIds())
		{
			const auto hit = spans.find(id + PerformanceShader::NATIVE_FP16_OFFSET);
			if (hit == spans.end())
				return Status::MissingShaders;
			std::vector<uint32> words = AdoptSpirvModule(hit->second);
			if (words.empty())
				return Status::TranslationFailed;
			modulesOut.emplace(id, std::move(words));
		}
		return Status::Ok;
	}

	bool RemoveInstalled()
	{
		std::error_code ec;
		const fs::path path = GetInstalledPath();
		if (!fs::exists(path, ec))
			return true;
		const bool removed = fs::remove(path, ec);
		NotifyChanged();
		return removed;
	}

	uint32 GetChangeCounter()
	{
		return s_changeCounter.load();
	}

	void NotifyChanged()
	{
		s_changeCounter.fetch_add(1);
	}
}
