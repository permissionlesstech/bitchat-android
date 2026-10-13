import unittest
from unittest import mock

from tools.release_gate import mesh_lab


class MeshLabScenarioDispatchTest(unittest.TestCase):
    def scenario_handlers(self):
        return {
            name: mock.Mock(return_value={"scenario": name})
            for name in mesh_lab.SCENARIOS
        }

    def test_phone_all_excludes_watch_only_scenarios(self):
        handlers = self.scenario_handlers()
        phone_a = mesh_lab.Device("phone-a", "alpha")
        phone_b = mesh_lab.Device("phone-b", "beta")

        with mock.patch.object(mesh_lab, "SCENARIOS", handlers):
            result = mesh_lab.run_scenario("all", phone_a, phone_b, out=None)

        self.assertEqual("pass", result["status"])
        handlers["watch_power"].assert_not_called()
        for name in mesh_lab.PHONE_SCENARIOS:
            handlers[name].assert_called_once_with(phone_a, phone_b)

    def test_watch_all_includes_watch_power(self):
        handlers = self.scenario_handlers()
        phone = mesh_lab.Device("phone", "alpha")
        watch = mesh_lab.WatchDevice("watch")

        with mock.patch.object(mesh_lab, "SCENARIOS", handlers):
            result = mesh_lab.run_scenario("all", phone, watch, out=None)

        self.assertEqual("pass", result["status"])
        handlers["watch_power"].assert_called_once_with(phone, watch)


if __name__ == "__main__":
    unittest.main()
