#include "AndroidInputHelpers.h"

#include "input/ControllerFactory.h"
#include "input/emulated/ClassicController.h"
#include "input/emulated/ProController.h"
#include "input/emulated/WiimoteController.h"

ControllerPtr CreateDefaultDeviceController()
{
	auto controller = ControllerFactory::CreateController(InputAPI::Device, "device", "device");
	controller->set_use_motion(true);
	return controller;
}

EmulatedControllerManager::EmulatedControllerManager(size_t index) : m_index(index) {}

EmulatedControllerManager& EmulatedControllerManager::GetController(size_t index)
{
	auto& controller = s_emulatedControllers.at(index);

	if (!controller)
	{
		controller = std::unique_ptr<EmulatedControllerManager>(new EmulatedControllerManager(index));
	}

	return *controller;
}

void EmulatedControllerManager::SetButtonValue(uint64 mappingId, bool value)
{
	auto emulatedController = GetControllerPtr();
	if (!emulatedController)
	{
		return;
	}

	emulatedController->setButtonValue(mappingId, value);
}

void EmulatedControllerManager::SetAxisValue(uint64 mappingId, float value)
{
	auto emulatedController = GetControllerPtr();
	if (!emulatedController)
	{
		return;
	}

	emulatedController->setAxisValue(mappingId, value);
}

void EmulatedControllerManager::SetType(EmulatedController::Type type)
{
	auto emulatedController = GetControllerPtr();
	if (emulatedController && emulatedController->type() == type)
	{
		return;
	}

	auto newEmulatedController = InputManager::instance().set_controller(m_index, type);

	// the touch screen, sensors and vibration of the device stay with the controller when its type changes
	if (emulatedController && newEmulatedController)
	{
		for (const auto& controller : emulatedController->get_controllers())
		{
			if (controller->api() == InputAPI::Device)
				newEmulatedController->add_controller(controller);
		}
	}
}

void EmulatedControllerManager::SetMapping(uint64 mappingId, ControllerPtr controller, uint64 buttonId)
{
	auto emulatedController = GetControllerPtr();
	if (!emulatedController || !controller)
	{
		return;
	}

	const auto& controllers = emulatedController->get_controllers();
	auto controllerIt = std::find_if(controllers.begin(), controllers.end(), [&](const ControllerPtr& c) { return c->api() == controller->api() && c->uuid() == controller->uuid(); });
	if (controllerIt == controllers.end())
		emulatedController->add_controller(controller);
	else
		controller = *controllerIt;
	emulatedController->set_mapping(mappingId, controller, buttonId);
}

std::optional<std::string> EmulatedControllerManager::GetMapping(uint64 mapping) const
{
	auto emulatedController = GetControllerPtr();
	if (!emulatedController)
		return {};
	auto controller = emulatedController->get_mapping_controller(mapping);
	if (!controller)
		return {};
	auto mappingName = emulatedController->get_mapping_name(mapping);
	return fmt::format("{}: {}", controller->display_name(), mappingName);
}

std::map<uint64, std::string> EmulatedControllerManager::GetMappings() const
{
	auto emulatedController = GetControllerPtr();
	if (!emulatedController)
		return {};

	std::map<uint64, std::string> mappings;
	EmulatedController::Type type = emulatedController->type();
	uint64 mapping = 0;
	uint64 maxMapping = 0;

	if (type == EmulatedController::Type::VPAD)
	{
		mapping = VPADController::ButtonId::kButtonId_A;
		maxMapping = VPADController::ButtonId::kButtonId_Max;
	}
	else if (type == EmulatedController::Type::Pro)
	{
		mapping = ProController::ButtonId::kButtonId_A;
		maxMapping = ProController::ButtonId::kButtonId_Max;
	}
	else if (type == EmulatedController::Type::Classic)
	{
		mapping = ClassicController::ButtonId::kButtonId_A;
		maxMapping = ClassicController::ButtonId::kButtonId_Max;
	}
	else if (type == EmulatedController::Type::Wiimote)
	{
		mapping = WiimoteController::ButtonId::kButtonId_A;
		maxMapping = WiimoteController::ButtonId::kButtonId_Max;
	}

	for (; mapping < maxMapping; mapping++)
	{
		if (auto mappingName = GetMapping(mapping))
		{
			mappings[mapping] = *mappingName;
		}
	}

	return mappings;
}

void EmulatedControllerManager::SetDisabled()
{
	InputManager::instance().delete_controller(m_index, true);
}

// not kept here, loading a profile replaces the controller
EmulatedControllerPtr EmulatedControllerManager::GetControllerPtr() const
{
	return InputManager::instance().get_controller(m_index);
}

void EmulatedControllerManager::ClearMapping(uint64 mapping)
{
	auto emulatedController = GetControllerPtr();
	if (!emulatedController)
	{
		return;
	}

	emulatedController->delete_mapping(mapping);
}
